package searchengine.services;

import searchengine.dto.indexing.IndexingResponse;
import searchengine.model.Page;

public interface IndexingService {
    void startIndexing();

    IndexingResponse stopIndexing();

    IndexingResponse indexPage(String url);

    void savePageLemmasToDB(Page page);
}
