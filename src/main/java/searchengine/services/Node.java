package searchengine.services;

import lombok.Data;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import searchengine.config.DOMConfiguration;
import searchengine.model.Page;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


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

    public List<Node> getChildren() throws IOException, InterruptedException {
        String urlAndPath = this.getSiteUrl() + page.getPath();
        Document doc = domConfiguration.getDocument(urlAndPath);
        if (doc == null) return new ArrayList<>();
        Elements references = Objects.requireNonNull(doc).select("a[href]");
        List<Node> childNodes = new ArrayList<>();
        for (Element ref : references) {
            if (ref.absUrl("href").startsWith(urlAndPath) && !ref.absUrl("href").contains("#")) {
                String url = ref.absUrl("href");
                Document childDoc = domConfiguration.getDocument(url);
                url = url.replace(ref.baseUri(), "/");
                childNodes.add(new Node(this.getSiteUrl(), Page.constructPage(url, this.page.getSite(), childDoc), domConfiguration));
            }
        }
        return childNodes;

    }

}
