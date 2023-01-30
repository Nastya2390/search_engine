package searchengine.utils;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import searchengine.config.DOMConfiguration;
import searchengine.model.Page;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Data
public class Node {

    private String siteUrl;
    private Page page;
    private DOMConfiguration domConfiguration;

    public Node(String siteUrl, Page page, DOMConfiguration domConfiguration) {
        this.siteUrl = siteUrl;
        this.page = page;
        this.domConfiguration = domConfiguration;
    }

    public Set<Node> getChildren() {
        String urlAndPath = this.getSiteUrl() + page.getPath();
        Document doc = domConfiguration.getDocument(urlAndPath);
        if (doc == null)
            return new HashSet<>();
        Elements references = Objects.requireNonNull(doc).select("a[href]");
        List<Element> refsWithoutPictures = references.stream()
                .filter(x -> !x.absUrl("href").toLowerCase(Locale.ROOT).endsWith("jpeg"))
                .filter(x -> !x.absUrl("href").toLowerCase(Locale.ROOT).endsWith("jpg"))
                .filter(x -> !x.absUrl("href").toLowerCase(Locale.ROOT).endsWith("png")).collect(Collectors.toList());
        Set<Node> childNodes = new HashSet<>();
        for (Element ref : refsWithoutPictures) {
            if (urlStartsWithSiteUrl(ref.absUrl("href"), urlAndPath) && !ref.absUrl("href").contains("#")) {
                String url = ref.absUrl("href");
                Document childDoc = domConfiguration.getDocument(url);
                url = getNodePath(url);
                if(url == null) {
                    log.warn("Fail to get node path - " + urlAndPath);
                    continue;
                }
                childNodes.add(new Node(this.getSiteUrl(), Page.constructPage(url, this.page.getSite(), childDoc), domConfiguration));
            }
        }
        return childNodes;
    }

    private String createUrlWithWWW(String url) {
        return url.replace("://", "://www.");
    }

    private boolean urlStartsWithSiteUrl(String nodeUrl, String siteUrl) {
        return nodeUrl.startsWith(siteUrl) || nodeUrl.startsWith(createUrlWithWWW(siteUrl));
    }

    private String getNodePath(String url) {
        String siteUrl = this.page.getSite().getUrl();
        String replacement = siteUrl.endsWith("/") ? "/" : "";
        if(url.contains(siteUrl)) {
            return url.replace(siteUrl, replacement);
        } else if(url.contains(createUrlWithWWW(siteUrl))) {
            return url.replace(createUrlWithWWW(siteUrl), replacement);
        } else {
            return null;
        }
    }

}
