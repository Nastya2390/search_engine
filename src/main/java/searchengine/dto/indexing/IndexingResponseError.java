package searchengine.dto.indexing;

import lombok.Data;

@Data
public class IndexingResponseError implements Response {
    private boolean result;
    private String error;

    public IndexingResponseError(boolean result, String error) {
        this.result = result;
        this.error = error;
    }
}
