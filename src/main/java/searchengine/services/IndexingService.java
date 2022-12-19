package searchengine.services;

import searchengine.dto.indexing.Response;

public interface IndexingService {
    Response startIndexing();

    Response stopIndexing();
}
