package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.DOMConfiguration;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.indexing.IndexingResponse;
import searchengine.dto.indexing.IndexingResponseError;
import searchengine.dto.indexing.Response;
import searchengine.model.Index;
import searchengine.model.IndexingStatus;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

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

import static searchengine.dto.indexing.ErrorMessage.IndexingIsNotRun;
import static searchengine.dto.indexing.ErrorMessage.IndexingIsNotStopped;
import static searchengine.dto.indexing.ErrorMessage.IndexingIsStoppedByUser;
import static searchengine.dto.indexing.ErrorMessage.InterruptedExceptionOccuredOnStopIndexing;
import static searchengine.dto.indexing.ErrorMessage.NoConnectionToSite;
import static searchengine.dto.indexing.ErrorMessage.NoSitesDataInConfigFile;
import static searchengine.dto.indexing.ErrorMessage.PageIsOutOfConfigFile;
import static searchengine.dto.indexing.ErrorMessage.SiteIsNotFoundByUrl;
import static searchengine.dto.indexing.ErrorMessage.SiteIsSeveralTimesUsedInConfig;

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
    public Response startIndexing() {
        SiteMapConstructor.isInterrupted = false;
        List<Site> sitesList = sites.getSites();
        if (sitesList.isEmpty())
            return new IndexingResponseError(false, NoSitesDataInConfigFile.getValue());
        deleteSitesRelatedInformation(sitesList.stream().map(Site::getName).collect(Collectors.toList()));
        fillSitePagesInfo(fillSitesInfo(sitesList));
        return new IndexingResponse(true);
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

    void deleteSitesRelatedInformation(List<String> siteNameList) {
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
        if (!SiteMapConstructor.isInterrupted) {
            for (searchengine.model.Site site : siteList) {
                site.setStatus(IndexingStatus.INDEXED);
                siteRepository.save(site);
            }
        }
    }

    @Override
    public Response stopIndexing() {
        try {
            if (pool == null)
                return new IndexingResponseError(false, IndexingIsNotRun.getValue());
            pool.shutdownNow();
            if (pool.awaitTermination(1, TimeUnit.MINUTES)) {
                return setFailedIndexingStatusForSites();
            } else {
                return new IndexingResponseError(false, IndexingIsNotStopped.getValue());
            }
        } catch (InterruptedException ie) {
            ie.printStackTrace();
            return new IndexingResponseError(false, InterruptedExceptionOccuredOnStopIndexing.getValue());
        }
    }

    private Response setFailedIndexingStatusForSites() {
        Optional<List<searchengine.model.Site>> siteListOpt = siteRepository.getSiteByStatus(IndexingStatus.INDEXING);
        if (!siteListOpt.isPresent() || siteListOpt.get().isEmpty())
            return new IndexingResponseError(false, IndexingIsNotRun.getValue());
        List<searchengine.model.Site> siteList = siteListOpt.get();
        for (searchengine.model.Site site : siteList) {
            site.setStatus(IndexingStatus.FAILED);
            site.setStatusTime(LocalDateTime.now());
            site.setLastError(IndexingIsStoppedByUser.getValue());
        }
        siteRepository.saveAll(siteList);
        return new IndexingResponse(true);
    }

    @Override
    public Response indexPage(String url) {
        url = getUrlWithoutWWW(url);
        List<String> sitesUrls = sites.getSites().stream().map(Site::getUrl)
                .map(this::getUrlWithoutWWW).filter(url::startsWith).collect(Collectors.toList());
        if (sitesUrls.isEmpty()) {
            return new IndexingResponseError(false, PageIsOutOfConfigFile.getValue());
        } else if (sitesUrls.size() > 1) {
            return new IndexingResponseError(false, SiteIsSeveralTimesUsedInConfig.getValue());
        }
        String siteUrl = sitesUrls.iterator().next();
        Optional<searchengine.model.Site> siteOpt = siteRepository.getSiteByUrl(siteUrl);
        if (!siteOpt.isPresent())
            return new IndexingResponseError(false, SiteIsNotFoundByUrl.getValue());
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
        if(!isPageCodeValid(page.getCode())) return;
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
