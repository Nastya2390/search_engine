package searchengine.dto.search;

import lombok.Data;

@Data
public class SearchRequestParams {
    private String query;
    private String site;
    private int offset;
    private int limit;

    public SearchRequestParams(String query, String site, int offset, int limit) {
        this.query = query;
        this.site = site;
        this.offset = offset;
        this.limit = limit;
    }

}
