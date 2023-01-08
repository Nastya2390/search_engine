package searchengine.services;


import searchengine.dto.search.Response;
import searchengine.dto.search.SearchRequestParams;

public interface SearchService {
    Response search(SearchRequestParams params);
}
