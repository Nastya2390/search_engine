package searchengine.services;

import lombok.Data;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import searchengine.model.Page;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

//@RequiredArgsConstructor
@Data
public class Node {

    public static final int LINKS_NUMBER = 4;
    private String siteUrl;
    private Page page;

    public Node(String siteUrl, Page page) {
        this.siteUrl = siteUrl;
        this.page = page;
    }

    public List<Node> getChildren() throws IOException, InterruptedException {
        try {
            String urlAndPath = this.getSiteUrl() + page.getPath();
            Document doc = Jsoup.connect(urlAndPath).userAgent("NastyaSearchBot")
                    .referrer("http://www.google.com")
                    .ignoreContentType(true).ignoreHttpErrors(true)
                    .maxBodySize(0).get();
            Thread.sleep(150);
            Elements references = doc.select("a[href]");
            List<Node> childNodes = new ArrayList<>();
            for (Element ref : references) {
                if (ref.absUrl("href").startsWith(urlAndPath) && !ref.absUrl("href").contains("#")) {
                    String url = ref.absUrl("href");
                    url = url.replace(ref.baseUri(), "/");
                    childNodes.add(new Node(this.getSiteUrl(), Page.constructPage(url, this.page.getSite(), doc)));
                }
            }
            return childNodes;
        } catch (HttpStatusException | InterruptedException e) {
            return Collections.EMPTY_LIST;
        }
    }

}
