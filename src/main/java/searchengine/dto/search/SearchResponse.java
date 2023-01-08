package searchengine.dto.search;

import lombok.Data;

import java.util.List;

@Data
public class SearchResponse implements Response {
    private boolean result;
    private long count;
    List<SearchData> data;

    public SearchResponse(boolean result, long count, List<SearchData> data) {
        this.result = result;
        this.count = count;
        this.data = data;
    }
}
