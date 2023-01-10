package searchengine.services;

import searchengine.dto.indexing.Response;
import searchengine.model.Page;

public interface IndexingService {
    Response startIndexing();

    Response stopIndexing();

    Response indexPage(String url);

    void savePageLemmasToDB(Page page);
}
