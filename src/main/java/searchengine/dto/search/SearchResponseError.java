package searchengine.dto.search;

import lombok.Data;

@Data
public class SearchResponseError implements Response {
    private boolean result;
    private String error;

    public SearchResponseError(boolean result, String error) {
        this.result = result;
        this.error = error;
    }
}
