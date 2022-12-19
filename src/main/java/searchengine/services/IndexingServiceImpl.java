package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.indexing.IndexingResponse;
import searchengine.dto.indexing.IndexingResponseError;
import searchengine.dto.indexing.Response;
import searchengine.model.IndexingStatus;
import searchengine.model.Page;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {

    private final SitesList sites;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private ForkJoinPool pool;

    @Override
    public Response startIndexing() {
        SiteMapConstructor.isInterrupted = false;
        List<Site> sitesList = sites.getSites();
        for (Site site : sitesList) {
            deleteSiteRelatedInformation(site.getName());
            searchengine.model.Site siteInfo = new searchengine.model.Site();
            createSiteInfo(siteInfo, site);
            addSitePages(siteInfo);
            // todo если произошла ошибка и обход завершить не удалось, изменять статус на FAILED и вносить в поле last_error понятную информацию о произошедшей ошибке.
        }
        return new IndexingResponse(true);
    }

    void createSiteInfo(searchengine.model.Site site, Site siteData) {
        site.setName(siteData.getName());
        String url = siteData.getUrl().replace("https://www.", "https://");
        url = url.replace("http://www.", "http://");
        site.setUrl(url);
        site.setStatus(IndexingStatus.INDEXING);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
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
        Node root = new Node(site.getUrl(), Page.constructPage(site.getUrl(), site));
        pool = new ForkJoinPool();
        pool.invoke(new SiteMapConstructor(root, pageRepository, siteRepository));
        if (!SiteMapConstructor.isInterrupted) {
            site.setStatus(IndexingStatus.INDEXED);
            siteRepository.save(site);
        }
    }

    @Override
    public Response stopIndexing() {
        try {
            if (pool == null) {
                return new IndexingResponseError(false, "Индексация не запущена");
            }
            pool.shutdownNow();
            if (pool.awaitTermination(1, TimeUnit.MINUTES)) {
                Optional<List<Integer>> siteIds = siteRepository.getIdByIndexingStatus(IndexingStatus.INDEXING);
                if (siteIds.isPresent()) {
                    for (Integer id : siteIds.get()) {
                        Optional<searchengine.model.Site> siteOpt = siteRepository.getSiteById(id);
                        if (siteOpt.isPresent()) {
                            searchengine.model.Site site = siteOpt.get();
                            site.setStatus(IndexingStatus.FAILED);
                            site.setStatusTime(LocalDateTime.now());
                            site.setLastError("Индексация остановлена пользователем");
                            siteRepository.save(site);
                        }
                    }
                } else {
                    return new IndexingResponseError(false, "Индексация не запущена");
                }
            } else {
                return new IndexingResponseError(false, "Индексация не остановлена");
            }
            return new IndexingResponse(true);
        } catch (InterruptedException ie) {
            ie.printStackTrace();
            return new IndexingResponseError(false, "Произошел InterruptedException при остановке индексации");
        }
    }

}
