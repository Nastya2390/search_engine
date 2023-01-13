package searchengine.services;

import exception.BadRequestException;
import exception.NotFoundException;
import exception.ServerErrorException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.DOMConfiguration;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.indexing.IndexingResponse;
import searchengine.model.Index;
import searchengine.model.IndexingStatus;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import javax.persistence.NonUniqueResultException;
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
import static searchengine.dto.ErrorMessage.NoConnectionToSite;
import static searchengine.dto.ErrorMessage.NoSitesDataInConfigFile;
import static searchengine.dto.ErrorMessage.PageIsOutOfConfigFile;
import static searchengine.dto.ErrorMessage.SiteIsNotFoundByUrl;
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

    @Override
    public IndexingResponse startIndexing() {
        SiteMapConstructor.isInterrupted = false;
        List<Site> sitesList = sites.getSites();
        if (sitesList.isEmpty())
            throw new NotFoundException(HttpStatus.NOT_FOUND, NoSitesDataInConfigFile.getValue());
        if (isIndexingInProcess()) {
            throw new ServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, IndexingIsInProcess.getValue());
        }
        deleteSitesRelatedInformation(sitesList.stream().map(Site::getName).collect(Collectors.toList()));
        fillSitePagesInfo(fillSitesInfo(sitesList));
        return new IndexingResponse(true);
    }

    private boolean isIndexingInProcess() {
        Optional<List<searchengine.model.Site>> siteListOpt = siteRepository.getSiteByStatus(IndexingStatus.INDEXING);
        return pool != null && siteListOpt.isPresent() && siteListOpt.get().size() > 0;
    }

    private List<searchengine.model.Site> fillSitesInfo(List<Site> sitesList) {
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
        return siteModelList;
    }

    String getUrlWithoutWWW(String url) {
        return url.replace("https://www.", "https://")
                .replace("http://www.", "http://");
    }

    void deleteSitesRelatedInformation(List<String> siteNameList) throws NonUniqueResultException {
        List<searchengine.model.Site> siteList = new ArrayList<>();
        for (String siteName : siteNameList) {
            Optional<searchengine.model.Site> siteOpt = siteRepository.getSiteByName(siteName);
            if (!siteOpt.isPresent()) return;
            searchengine.model.Site site = siteOpt.get();
            siteList.add(site);
            deleteAllSitePages(site);
            Optional<List<Lemma>> lemmaListOpt = lemmaRepository.getLemmaBySite(site);
            if (!lemmaListOpt.isPresent() || lemmaListOpt.get().isEmpty()) return;
            lemmaRepository.deleteAll(lemmaListOpt.get());
        }
        siteRepository.deleteAll(siteList);
    }

    void deleteAllSitePages(searchengine.model.Site site) {
        List<Page> pages = pageRepository.getPageBySiteId(site.getId());
        for (Page page : pages) {
            Optional<List<Index>> indexListOpt = indexRepository.getIndexByPageId(page.getId());
            if (!indexListOpt.isPresent() || indexListOpt.get().isEmpty()) return;
            indexRepository.deleteAll(indexListOpt.get());
        }
        pageRepository.deleteAll(pages);
    }

    void fillSitePagesInfo(List<searchengine.model.Site> siteList) {
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
            rootNodes.add(new Node(site.getUrl(), rootPage, domConfiguration));
        }
        pool = new ForkJoinPool();
        pool.invoke(new SiteMapConstructor(rootNodes, pageRepository, siteRepository, this));
        changeSitesStatusToIndexed(siteList);
    }

    private void changeSitesStatusToIndexed(List<searchengine.model.Site> siteList) {
        // todo исключить сайты с ошибкой индексации
        if (!SiteMapConstructor.isInterrupted) {
            for (searchengine.model.Site site : siteList) {
                site.setStatus(IndexingStatus.INDEXED);
                siteRepository.save(site);
            }
        }
    }

    @Override
    public IndexingResponse stopIndexing() {
        try {
            if (pool == null)
                throw new BadRequestException(HttpStatus.BAD_REQUEST, IndexingIsNotRun.getValue());
            pool.shutdownNow();
            if (pool.awaitTermination(1, TimeUnit.MINUTES)) {
                setFailedIndexingStatusForSites();
            } else {
                throw new ServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, IndexingIsNotStopped.getValue());
            }
            return new IndexingResponse(true);
        } catch (InterruptedException e) {
            log.error(e.getMessage(), e);
            throw new ServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, InterruptedExceptionOccuredOnStopIndexing.getValue());
        }
    }

    private void setFailedIndexingStatusForSites() {
        Optional<List<searchengine.model.Site>> siteListOpt = siteRepository.getSiteByStatus(IndexingStatus.INDEXING);
        if (!siteListOpt.isPresent() || siteListOpt.get().isEmpty())
            throw new BadRequestException(HttpStatus.BAD_REQUEST, IndexingIsNotRun.getValue());
        List<searchengine.model.Site> siteList = siteListOpt.get();
        for (searchengine.model.Site site : siteList) {
            site.setStatus(IndexingStatus.FAILED);
            site.setStatusTime(LocalDateTime.now());
            site.setLastError(IndexingIsStoppedByUser.getValue());
        }
        siteRepository.saveAll(siteList);
    }

    @Override
    public IndexingResponse indexPage(String url) {
        url = getUrlWithoutWWW(url);
        List<String> sitesUrls = sites.getSites().stream().map(Site::getUrl)
                .map(this::getUrlWithoutWWW).filter(url::startsWith).collect(Collectors.toList());
        if (sitesUrls.isEmpty()) {
            throw new BadRequestException(HttpStatus.BAD_REQUEST, PageIsOutOfConfigFile.getValue());
        } else if (sitesUrls.size() > 1) {
            throw new ServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, SiteIsSeveralTimesUsedInConfig.getValue());
        }
        String siteUrl = sitesUrls.iterator().next();
        Optional<searchengine.model.Site> siteOpt = siteRepository.getSiteByUrl(siteUrl);
        if (!siteOpt.isPresent())
            throw new NotFoundException(HttpStatus.NOT_FOUND, SiteIsNotFoundByUrl.getValue());
        searchengine.model.Site site = siteOpt.get();
        String pagePath = url.replaceFirst(siteUrl, "");
        Optional<Page> pageOpt = pageRepository.getPageByPathAndSite(pagePath, site);
        if (pageOpt.isPresent()) {
            savePageLemmasToDB(pageOpt.get());
        } else {
            addNewPageToDB(site, pagePath);
        }
        return new IndexingResponse(true);
    }

    private void addNewPageToDB(searchengine.model.Site site, String pagePath) {
        String urlAndPath = site.getUrl() + pagePath;
        Document doc = domConfiguration.getDocument(urlAndPath);
        if (doc != null) {
            Page page = Page.constructPage(pagePath, site, doc);
            pageRepository.save(page);
            savePageLemmasToDB(page);
        }
    }

    public void savePageLemmasToDB(Page page) {
        if (!isPageCodeValid(page.getCode())) return;
        deletePreviousPageIndexingInfo(page);
        searchengine.model.Site site = page.getSite();
        String textWithoutTags = lemmasFinder.deleteHtmlTags(page.getContent());
        Map<String, Integer> lemmas = lemmasFinder.getTextLemmas(textWithoutTags);
        List<Index> indexList = new ArrayList<>();
        for (String key : lemmas.keySet()) {
            Lemma lemma = fillLemmaInfo(site, key);
            Index index = new Index();
            index.setPage(page);
            index.setLemma(lemma);
            index.setRank(lemmas.get(key));
            indexList.add(index);
        }
        indexRepository.saveAll(indexList);
    }

    private boolean isPageCodeValid(int code) {
        return !String.valueOf(code).substring(0, 1).matches("[4,5]");
    }

    @Transactional
    public Lemma fillLemmaInfo(searchengine.model.Site site, String word) {
        Lemma lemma;
        Optional<List<Lemma>> lemmasOpt = lemmaRepository.getLemmaBySiteAndLemma(site, word);
        if (lemmasOpt.isPresent() && lemmasOpt.get().size() > 0) {
            // todo assert на число лемм найденных
            lemma = lemmasOpt.get().iterator().next();
            lemma.setFrequency(lemma.getFrequency() + 1);
        } else {
            lemma = new Lemma();
            lemma.setLemma(word);
            lemma.setFrequency(1);
            lemma.setSite(site);
        }
        lemmaRepository.save(lemma);
        return lemma;
    }

    private void deletePreviousPageIndexingInfo(Page page) {
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
    }

}
