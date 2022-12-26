package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.apache.lucene.morphology.LuceneMorphology;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
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
        for (Site site : sitesList) {
            deleteSiteRelatedInformation(site.getName());
            searchengine.model.Site siteModel = fillSiteInfo(site);
            addSitePages(siteModel);
            // todo если произошла ошибка и обход завершить не удалось, изменять статус на FAILED и вносить в поле last_error понятную информацию о произошедшей ошибке.
        }
        return new IndexingResponse(true);
    }

    private searchengine.model.Site fillSiteInfo(Site siteData) {
        searchengine.model.Site siteModel = new searchengine.model.Site();
        siteModel.setName(siteData.getName());
        String url = getUrlWithoutWWW(siteData.getUrl());
        siteModel.setUrl(url);
        siteModel.setStatus(IndexingStatus.INDEXING);
        siteModel.setStatusTime(LocalDateTime.now());
        siteRepository.save(siteModel);
        return siteModel;
    }

    String getUrlWithoutWWW(String url) {
        return url.replace("https://www.", "https://")
                .replace("http://www.", "http://");
    }

    void deleteSiteRelatedInformation(String siteName) {
        Optional<Integer> siteId = siteRepository.getIdBySiteName(siteName);
        if (siteId.isPresent()) {
            List<Page> pages = pageRepository.getPageBySiteId(siteId.get());
            pageRepository.deleteAll(pages);
            siteRepository.deleteById(siteId.get());
        }
    }

    void addSitePages(searchengine.model.Site site) {
        Page rootPage = Page.constructPage("/", site, domConfiguration.getDocument(site.getUrl()));
        Node root = new Node(site.getUrl(), rootPage, domConfiguration);
        pool = new ForkJoinPool();
        pool.invoke(new SiteMapConstructor(root, pageRepository, siteRepository, this));
        if (!SiteMapConstructor.isInterrupted) {
            site.setStatus(IndexingStatus.INDEXED);
            siteRepository.save(site);
        }
        if (isPageCodeValid(Objects.requireNonNull(rootPage).getCode()))
            indexPage(site.getUrl() + Objects.requireNonNull(rootPage).getPath());
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
        Optional<Page> pageOpt = pageRepository.getPageByPath(pagePath);
        pageOpt.ifPresent(page -> savePageLemmasToDB(page, siteOpt.get()));
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
        deletePreviousPageIndexingInfo(page, site);
        LemmasFinder lemmasFinder = new LemmasFinder(luceneMorphology);
        String textWithoutTags = lemmasFinder.deleteHtmlTags(page.getContent());
        Map<String, Integer> lemmas = lemmasFinder.getTextLemmas(textWithoutTags);
        for (String key : lemmas.keySet()) {
            Lemma lemma = fillLemmaInfo(site, key);
            lemmaRepository.save(lemma);
            Index index = fillIndexInfo(page, lemma, lemmas.get(key));
            indexRepository.save(index);
        }
    }

    private Lemma fillLemmaInfo(searchengine.model.Site site, String word) {
        Lemma lemma = new Lemma();
        lemma.setLemma(word);
        if (lemma.getSites() == null) {
            List<searchengine.model.Site> sites = new ArrayList<>();
            sites.add(site);
            lemma.setSites(sites);
            lemma.setFrequency(1);
        } else {
            lemma.getSites().add(site);
            lemma.setFrequency(lemma.getSites().size());
        }
        return lemma;
    }

    private Index fillIndexInfo(Page page, Lemma lemma, double rank) {
        Index index = new Index();
        index.setPage(page);
        index.setLemma(lemma);
        index.setRank(rank);
        return index;
    }

    private void deletePreviousPageIndexingInfo(Page page, searchengine.model.Site site) {
        Document doc = domConfiguration.getDocument(site.getUrl() + page.getPath());
        page.setContent(Objects.requireNonNull(doc).toString());
        page.setCode(doc.connection().response().statusCode());
        pageRepository.save(Objects.requireNonNull(page));
        Optional<List<Index>> indexListOpt = indexRepository.getIndexByPageId(page.getId());
        if (indexListOpt.isPresent()) {
            List<Index> indexList = indexListOpt.get();
            for (Index index : indexList) {
                if (index.getLemma().getSites().size() == 1) {
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
