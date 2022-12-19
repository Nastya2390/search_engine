package searchengine.services;

import lombok.Data;
import searchengine.model.Site;

@Data
public class SiteInfo {

    private String path;

    private int code;

    private String content;

    private Site site;

}
