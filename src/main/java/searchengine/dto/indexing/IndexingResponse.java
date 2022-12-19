package searchengine.dto.indexing;

import lombok.Data;

@Data
public class IndexingResponse implements Response {
    private boolean result;

    public IndexingResponse(boolean result) {
        this.result = result;
    }
}
