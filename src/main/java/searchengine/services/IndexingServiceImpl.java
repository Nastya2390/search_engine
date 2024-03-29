package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import searchengine.config.DOMConfiguration;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.indexing.IndexingResponse;
import searchengine.exceptions.BadRequestException;
import searchengine.exceptions.NotFoundException;
import searchengine.exceptions.ServerErrorException;
import searchengine.model.Index;
import searchengine.model.IndexingStatus;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.utils.LemmasFinder;
import searchengine.utils.Node;
import searchengine.utils.SiteMapConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static searchengine.dto.ErrorMessage.IndexingIsInProcess;
import static searchengine.dto.ErrorMessage.IndexingIsNotRun;
import static searchengine.dto.ErrorMessage.IndexingIsNotStopped;
import static searchengine.dto.ErrorMessage.IndexingIsStoppedByUser;
import static searchengine.dto.ErrorMessage.InterruptedExceptionOccuredOnStopIndexing;
import static searchengine.dto.ErrorMessage.LemmasDoublesFoundAtOneSite;
import static searchengine.dto.ErrorMessage.NoConnectionToSite;
import static searchengine.dto.ErrorMessage.NoSitesDataInConfigFile;
import static searchengine.dto.ErrorMessage.PageIsOutOfConfigFile;
import static searchengine.dto.ErrorMessage.SiteIsSeveralTimesUsedInConfig;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {

    private final SitesList sites;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final DOMConfiguration domConfiguration;
    private final LemmasFinder lemmasFinder;
    private ForkJoinPool pool;

    @Async
    @Override
    public void startIndexing() {
        long start = System.currentTimeMillis();
        boolean isIndexingAlreadyStarted = isIndexingInProcess();
        SiteMapConstructor.indexingRunning = true;
        SiteMapConstructor.isInterrupted = false;
        List<Site> sitesList = sites.getSites();
        deleteBackslashAtTheEndOfSiteUrl(sitesList);
        if (sitesList.isEmpty())
            throw new NotFoundException(HttpStatus.NOT_FOUND, NoSitesDataInConfigFile.getValue());
        if (isIndexingAlreadyStarted) {
            throw new ServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, IndexingIsInProcess.getValue());
        }
        deleteSitesRelatedInformation(sitesList.stream().map(Site::getName).collect(Collectors.toList()));
        fillSitePagesInfo(fillSitesInfo(sitesList));
        SiteMapConstructor.indexingRunning = false;
        log.debug("startIndexing - " + (System.currentTimeMillis() - start) + " ms");
    }

    private void deleteBackslashAtTheEndOfSiteUrl(List<Site> sitesList) {
        for (Site site : sitesList) {
            if (site.getUrl().endsWith("/")) {
                site.setUrl(site.getUrl().substring(0, site.getUrl().length() - 1));
            }
        }
    }

    private boolean isIndexingInProcess() {
        Optional<List<searchengine.model.Site>> siteListOpt = siteRepository.getSiteByStatus(IndexingStatus.INDEXING);
        return SiteMapConstructor.indexingRunning && siteListOpt.isPresent() && siteListOpt.get().size() > 0;
    }

    private List<searchengine.model.Site> fillSitesInfo(List<Site> sitesList) {
        long start = System.currentTimeMillis();
        List<searchengine.model.Site> siteModelList = new ArrayList<>();
        for (Site siteData : sitesList) {
            searchengine.model.Site siteModel = new searchengine.model.Site();
            siteModel.setName(siteData.getName());
            String url = getUrlWithoutWWW(siteData.getUrl());
            siteModel.setUrl(url);
            siteModel.setStatus(IndexingStatus.INDEXING);
            siteModel.setStatusTime(LocalDateTime.now());
            siteRepository.save(siteModel);
            siteModelList.add(siteModel);
        }
        log.debug("fillSitesInfo - " + (System.currentTimeMillis() - start) + " ms");
        return siteModelList;
    }

    String getUrlWithoutWWW(String url) {
        return url.replace("https://www.", "https://")
                .replace("http://www.", "http://");
    }

    void deleteSitesRelatedInformation(List<String> siteNameList) {
        long start = System.currentTimeMillis();
        List<searchengine.model.Site> siteList = new ArrayList<>();
        for (String siteName : siteNameList) {
            Optional<searchengine.model.Site> siteOpt = siteRepository.getSiteByName(siteName);
            if (!siteOpt.isPresent()) continue;
            searchengine.model.Site site = siteOpt.get();
            siteList.add(site);
            deleteAllSitePages(site);
            Optional<List<Lemma>> lemmaListOpt = lemmaRepository.getLemmaBySite(site);
            if (!lemmaListOpt.isPresent() || lemmaListOpt.get().isEmpty()) continue;
            lemmaRepository.deleteAll(lemmaListOpt.get());
        }
        siteRepository.deleteAll(siteList);
        log.debug("deleteSitesRelatedInformation - " + (System.currentTimeMillis() - start) + " ms");
    }

    void deleteAllSitePages(searchengine.model.Site site) {
        long start = System.currentTimeMillis();
        List<Page> pages = pageRepository.getPageBySiteId(site.getId());
        for (Page page : pages) {
            Optional<List<Index>> indexListOpt = indexRepository.getIndexByPageId(page.getId());
            if (!indexListOpt.isPresent() || indexListOpt.get().isEmpty()) continue;
            indexRepository.deleteAll(indexListOpt.get());
        }
        pageRepository.deleteAll(pages);
        log.debug("deleteAllSitePages - " + (System.currentTimeMillis() - start) + " ms");
    }

    void fillSitePagesInfo(List<searchengine.model.Site> siteList) {
        long start = System.currentTimeMillis();
        List<Node> rootNodes = new ArrayList<>();
        for (searchengine.model.Site site : siteList) {
            Document doc = domConfiguration.getDocument(site.getUrl());
            if (doc == null) {
                site.setStatus(IndexingStatus.FAILED);
                site.setLastError(NoConnectionToSite.getValue());
                siteRepository.save(site);
                continue;
            }
            Page rootPage = Page.constructPage("/", site, doc);
            log.debug("fillSitePagesInfo - " + rootPage);
            rootNodes.add(new Node(site.getUrl(), rootPage, domConfiguration));
        }
        pool = new ForkJoinPool();
        pool.invoke(new SiteMapConstructor(rootNodes, pageRepository, siteRepository, this));
        log.debug("fillSitePagesInfo - " + (System.currentTimeMillis() - start) + " ms");
    }

    @Override
    public IndexingResponse stopIndexing() {
        try {
            log.debug("stopIndexing started");
            if (!SiteMapConstructor.indexingRunning)
                throw new BadRequestException(HttpStatus.BAD_REQUEST, IndexingIsNotRun.getValue());
            SiteMapConstructor.isInterrupted = true;
            if(pool != null) {
                pool.shutdownNow();
                if (pool.awaitTermination(5, TimeUnit.MINUTES)) {
                    setFailedIndexingStatusForSites();
                } else {
                    throw new ServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, IndexingIsNotStopped.getValue());
                }
            }
            SiteMapConstructor.indexingRunning = false;
            log.debug("stopIndexing finished successfully");
            return new IndexingResponse(true);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            throw new ServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, InterruptedExceptionOccuredOnStopIndexing.getValue());
        }
    }

    private void setFailedIndexingStatusForSites() {
        long start = System.currentTimeMillis();
        List<Site> sitesList = sites.getSites();
        if (sitesList.isEmpty())
            throw new BadRequestException(HttpStatus.BAD_REQUEST, NoSitesDataInConfigFile.getValue());
        List<searchengine.model.Site> siteList = new ArrayList<>();
        for (Site site : sitesList) {
            Optional<searchengine.model.Site> siteOpt = siteRepository.getSiteByName(site.getName());
            if (!siteOpt.isPresent()) continue;
            searchengine.model.Site siteModel = siteOpt.get();
            siteModel.setStatus(IndexingStatus.FAILED);
            siteModel.setStatusTime(LocalDateTime.now());
            siteModel.setLastError(IndexingIsStoppedByUser.getValue());
            siteList.add(siteModel);
        }
        siteRepository.saveAll(siteList);
        log.debug("setFailedIndexingStatusForSites - " + (System.currentTimeMillis() - start) + " ms");
    }

    @Override
    public IndexingResponse indexPage(String url) {
        long start = System.currentTimeMillis();
        final String urlWithoutWWW = getUrlWithoutWWW(url);
        List<Site> sitesList = sites.getSites().stream()
                .filter(x -> urlWithoutWWW.startsWith(getUrlWithoutWWW(x.getUrl()))).collect(Collectors.toList());
        deleteBackslashAtTheEndOfSiteUrl(sitesList);
        if (sitesList.isEmpty()) {
            throw new BadRequestException(HttpStatus.BAD_REQUEST, PageIsOutOfConfigFile.getValue());
        } else if (sitesList.size() > 1) {
            throw new ServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, SiteIsSeveralTimesUsedInConfig.getValue());
        }
        String siteUrl = getUrlWithoutWWW(sitesList.iterator().next().getUrl());
        Optional<searchengine.model.Site> siteOpt = siteRepository.getSiteByUrl(siteUrl);
        searchengine.model.Site site;
        if (!siteOpt.isPresent()) {
            List<searchengine.model.Site> sites = fillSitesInfo(sitesList);
            site = sites.iterator().next();
        } else {
            site = siteOpt.get();
        }
        String pagePath = urlWithoutWWW.replaceFirst(siteUrl, "");
        Optional<Page> pageOpt = pageRepository.getPageByPathAndSite(pagePath, site);
        if (pageOpt.isPresent()) {
            savePageLemmasToDB(pageOpt.get());
        } else {
            addNewPageToDB(site, pagePath);
        }
        System.out.println("indexPage - " + (System.currentTimeMillis() - start) + " ms");
        return new IndexingResponse(true);
    }

    private void addNewPageToDB(searchengine.model.Site site, String pagePath) {
        long start = System.currentTimeMillis();
        String urlAndPath = site.getUrl() + pagePath;
        Document doc = domConfiguration.getDocument(urlAndPath);
        if (doc != null) {
            Page page = Page.constructPage(pagePath, site, doc);
            pageRepository.save(page);
            savePageLemmasToDB(page);
        }
        log.debug("addNewPageToDB - " + (System.currentTimeMillis() - start) + " ms");
    }

    public void savePageLemmasToDB(Page page) {
        long start = System.currentTimeMillis();
        if (Page.pageCodeNotValid(page.getCode())) return;
        deletePreviousPageIndexingInfo(page);
        searchengine.model.Site site = page.getSite();
        String textWithoutTags = lemmasFinder.deleteHtmlTags(page.getContent());
        Map<String, Integer> lemmas = lemmasFinder.getTextRusEngLemmas(textWithoutTags);
        List<Index> indexList = new ArrayList<>();
        Lemma lemma;
        for (String key : lemmas.keySet()) {
            lemma = fillLemmaInfo(site, key);
            Index index = new Index();
            index.setPage(page);
            index.setLemma(lemma);
            index.setRank(lemmas.get(key));
            indexList.add(index);
        }
        indexRepository.saveAll(indexList);
        log.debug("savePageLemmasToDB - " + (System.currentTimeMillis() - start) + " ms - ");
    }

    private synchronized Lemma fillLemmaInfo(searchengine.model.Site site, String word) {
        long start = System.currentTimeMillis();
        Lemma lemma;
        Optional<List<Lemma>> lemmasOpt = lemmaRepository.getLemmaByLemmaAndSite(word, site);
        if (lemmasOpt.isPresent() && lemmasOpt.get().size() > 0) {
            if (lemmasOpt.get().size() == 1) {
                lemma = lemmasOpt.get().iterator().next();
                lemma.setFrequency(lemma.getFrequency() + 1);
            } else {
                throw new ServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, LemmasDoublesFoundAtOneSite.getValue());
            }
        } else {
            lemma = new Lemma();
            lemma.setLemma(word);
            lemma.setFrequency(1);
            lemma.setSite(site);
        }
        lemmaRepository.save(lemma);
        log.debug("fillLemmaInfo - " + (System.currentTimeMillis() - start) + " ms");
        return lemma;
    }

    private void deletePreviousPageIndexingInfo(Page page) {
        long start = System.currentTimeMillis();
        Optional<List<Index>> indexListOpt = indexRepository.getIndexByPageId(page.getId());
        if (!indexListOpt.isPresent()) return;
        List<Index> indexList = indexListOpt.get();
        Set<Lemma> lemmasToDelete = new HashSet<>();
        for (Index index : indexList) {
            if (index.getLemma().getFrequency() == 1) {
                lemmasToDelete.add(index.getLemma());
            } else {
                index.getLemma().setFrequency(index.getLemma().getFrequency() - 1);
            }
        }
        indexRepository.deleteAll(indexList);
        lemmaRepository.deleteAll(lemmasToDelete);
        log.debug("deletePreviousPageIndexingInfo - " + (System.currentTimeMillis() - start) + " ms");
    }

}
