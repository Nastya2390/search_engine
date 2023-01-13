package searchengine.services;


import searchengine.dto.search.SearchRequestParams;
import searchengine.dto.search.SearchResponse;

public interface SearchService {
    SearchResponse search(SearchRequestParams params);
}
