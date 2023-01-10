package searchengine.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import searchengine.dto.indexing.Response;
import searchengine.dto.search.SearchRequestParams;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.IndexingService;
import searchengine.services.SearchService;
import searchengine.services.StatisticsService;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final StatisticsService statisticsService;
    private final IndexingService indexingService;
    private final SearchService searchService;

    public ApiController(StatisticsService statisticsService, IndexingService indexingService, SearchService searchService) {
        this.statisticsService = statisticsService;
        this.indexingService = indexingService;
        this.searchService = searchService;
    }

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<Response> startIndexing() {
        return ResponseEntity.ok(indexingService.startIndexing());
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<Response> stopIndexing() {
        return ResponseEntity.ok(indexingService.stopIndexing());
    }

    @PostMapping(value = "/indexPage")
    public ResponseEntity<Response> indexPage(@RequestParam(value = "url", defaultValue = "") String url) {
        return ResponseEntity.ok(indexingService.indexPage(url));
    }

    @GetMapping("/search")
    public ResponseEntity<searchengine.dto.search.Response> search(@RequestParam(value = "query", defaultValue = "") String query,
                                                                   @RequestParam(value = "site", defaultValue = "all") String site,
                                                                   @RequestParam(value = "offset", defaultValue = "0") String offset,
                                                                   @RequestParam(value = "limit", defaultValue = "20") String limit) {
        SearchRequestParams params = new SearchRequestParams(query, site,
                Integer.parseInt(offset), Integer.parseInt(limit));
        return ResponseEntity.ok(searchService.search(params));
    }

}
