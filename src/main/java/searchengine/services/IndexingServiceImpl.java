package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.apache.lucene.morphology.LuceneMorphology;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final LuceneMorphology luceneMorphology;
    private final DOMConfiguration domConfiguration;
    private ForkJoinPool pool;

    @Override
    public Response startIndexing() {
        SiteMapConstructor.isInterrupted = false;
        List<Site> sitesList = sites.getSites();
        if (sitesList.isEmpty())
            return new IndexingResponseError(false, NoSitesDataInConfigFile.getValue());
        deleteSitesRelatedInformation(sitesList.stream().map(Site::getName).collect(Collectors.toList()));
        fillSitePagesInfo(fillSitesInfo(sitesList));
        // todo если произошла ошибка и обход завершить не удалось, изменять статус на FAILED и вносить в поле last_error понятную информацию о произошедшей ошибке.
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
        for (String siteName : siteNameList) {
            Optional<Integer> siteId = siteRepository.getIdBySiteName(siteName);
            if (siteId.isPresent()) {
                Optional<searchengine.model.Site> siteOpt = siteRepository.getSiteById(siteId.get());
                List<Page> pages = pageRepository.getPageBySiteId(siteId.get());
                for (Page page : pages) {
                    Optional<List<Index>> indexListOpt = indexRepository.getIndexByPageId(page.getId());
                    // todo проверить что не пустой
                    indexRepository.deleteAll(indexListOpt.get());
                }
                pageRepository.deleteAll(pages);
                Optional<List<Lemma>> lemmaListOpt = lemmaRepository.getLemmaBySite(siteOpt.get());
                lemmaRepository.deleteAll(lemmaListOpt.get());
                siteRepository.deleteById(siteId.get());
            }
        }
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
        //indexAllPages(siteList);
        changeSitesStatusToIndexed(siteList);
    }

    private void indexAllPages(List<searchengine.model.Site> siteList) {
        for (searchengine.model.Site site : siteList) {
            if (!SiteMapConstructor.isInterrupted) {
                Optional<Integer> siteId = siteRepository.getIdBySiteName(site.getName());
                if (!siteId.isPresent()) return;
                List<Page> pages = pageRepository.getPageBySiteId(siteId.get());
                for (Page page : pages) {
                    if (isPageCodeValid(Objects.requireNonNull(page).getCode()))
                        indexPage(site.getUrl() + page.getPath());
                }
            }
        }
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
        Optional<List<Integer>> siteIds = siteRepository.getIdByIndexingStatus(IndexingStatus.INDEXING);
        if (!siteIds.isPresent())
            return new IndexingResponseError(false, IndexingIsNotRun.getValue());
        for (Integer id : siteIds.get()) {
            Optional<searchengine.model.Site> siteOpt = siteRepository.getSiteById(id);
            if (siteOpt.isPresent()) {
                searchengine.model.Site site = siteOpt.get();
                site.setStatus(IndexingStatus.FAILED);
                site.setStatusTime(LocalDateTime.now());
                site.setLastError(IndexingIsStoppedByUser.getValue());
                siteRepository.save(site);
            }
        }
        return new IndexingResponse(true);
    }

    @Override
    public Response indexPage(String url) {
        url = getUrlWithoutWWW(url);
        List<String> sitesUrls = sites.getSites().stream().map(Site::getUrl)
                .map(this::getUrlWithoutWWW).filter(url::startsWith).collect(Collectors.toList());
        String errorMessage = checkMatchedSites(sitesUrls);
        if (errorMessage != null)
            return new IndexingResponseError(false, errorMessage);
        String siteUrl = sitesUrls.iterator().next();
        Optional<searchengine.model.Site> siteOpt = siteRepository.getSiteByUrl(siteUrl);
        if (!siteOpt.isPresent())
            return new IndexingResponseError(false, SiteIsNotFoundByUrl.getValue());
        String pagePath = url.replaceFirst(siteUrl, "");
        Optional<Page> pageOpt = pageRepository.getPageByPathAndSite(pagePath, siteOpt.get());
        pageOpt.ifPresent(page -> savePageLemmasToDB(page, siteOpt.get()));
        //todo если старницы не было в базе - добавить
        return new IndexingResponse(true);
    }

    private String checkMatchedSites(List<String> sitesUrls) {
        if (sitesUrls.isEmpty()) {
            return PageIsOutOfConfigFile.getValue();
        } else if (sitesUrls.size() > 1) {
            return SiteIsSeveralTimesUsedInConfig.getValue();
        }
        return null;
    }

    private void savePageLemmasToDB(Page page, searchengine.model.Site site) {
        deletePreviousPageIndexingInfo(page);
        LemmasFinder lemmasFinder = new LemmasFinder(luceneMorphology);
        String textWithoutTags = lemmasFinder.deleteHtmlTags(page.getContent());
        Map<String, Integer> lemmas = lemmasFinder.getTextLemmas(textWithoutTags);
        for (String key : lemmas.keySet()) {
            Lemma lemma = fillLemmaInfo(site, key);
            fillIndexInfo(page, lemma, lemmas.get(key));
        }
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

    private void fillIndexInfo(Page page, Lemma lemma, double rank) {
        Index index = new Index();
        index.setPage(page);
        index.setLemma(lemma);
        index.setRank(rank);
        indexRepository.save(index);
    }

    private void deletePreviousPageIndexingInfo(Page page) {
        Optional<List<Index>> indexListOpt = indexRepository.getIndexByPageId(page.getId());
        if (indexListOpt.isPresent()) {
            List<Index> indexList = indexListOpt.get();
            for (Index index : indexList) {
                if (index.getLemma().getFrequency() == 1) {
                    lemmaRepository.delete(index.getLemma());
                } else {
                    index.getLemma().setFrequency(index.getLemma().getFrequency() - 1);
                }
                indexRepository.delete(index);
            }
        }
    }

    public boolean isPageCodeValid(int code) {
        return !String.valueOf(code).startsWith("[4,5]");
    }

}
