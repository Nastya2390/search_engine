package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.model.Lemma;
import searchengine.model.Site;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.SiteRepository;

import java.sql.Date;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static searchengine.model.IndexingStatus.INDEXED;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final SiteRepository siteRepository;
    private final LemmaRepository lemmaRepository;

    @Override
    public StatisticsResponse getStatistics() {
        List<Site> sitesList = siteRepository.findAll();
        TotalStatistics total = new TotalStatistics();
        total.setSites(sitesList.size());
        total.setIndexing(getSearchEngineIndexingStatus(sitesList));
        StatisticsResponse response = new StatisticsResponse();
        StatisticsData data = new StatisticsData();
        data.setTotal(total);
        List<DetailedStatisticsItem> detailed = fillDetailedStatisticsItemList(sitesList, total);
        data.setDetailed(detailed);
        response.setStatistics(data);
        response.setResult(true);
        return response;
    }

    private List<DetailedStatisticsItem> fillDetailedStatisticsItemList(List<Site> sitesList, TotalStatistics total) {
        List<DetailedStatisticsItem> detailed = new ArrayList<>();
        for (Site site : sitesList) {
            DetailedStatisticsItem item = new DetailedStatisticsItem();
            item.setName(site.getName());
            item.setUrl(site.getUrl());
            int pages = site.getPages().size();
            Optional<List<Lemma>> lemmaListOpt = lemmaRepository.getLemmaBySite(site);
            int lemmas = lemmaListOpt.map(List::size).orElse(0);
            item.setPages(pages);
            item.setLemmas(lemmas);
            item.setStatus(site.getStatus().toString());
            item.setError(site.getLastError() == null ? "" : site.getLastError());
            item.setStatusTime(Date.from(site.getStatusTime().atZone(ZoneId.systemDefault()).toInstant()).getTime());
            total.setPages(total.getPages() + pages);
            total.setLemmas(total.getLemmas() + lemmas);
            detailed.add(item);
        }
        return detailed;
    }

    Boolean getSearchEngineIndexingStatus(List<Site> sitesList) {
        return sitesList.stream().map(Site::getStatus).allMatch(x -> INDEXED.name().equals(x.name()));
    }

}
