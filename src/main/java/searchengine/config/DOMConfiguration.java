package searchengine.config;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConstructorBinding;
import org.springframework.stereotype.Component;

@Component
@ConstructorBinding
public class DOMConfiguration {

    @Value("${dom-settings.userAgent}")
    private String userAgent;

    @Value("${dom-settings.referer}")
    private String referer;

    @Value("${dom-settings.delayMs}")
    private String delayMs;


    public Document getDocument(String url) {
        try {
            Document doc = Jsoup.connect(url).userAgent(userAgent)
                    .referrer(referer)
                    .ignoreContentType(true).ignoreHttpErrors(true)
                    .maxBodySize(0).get();
            Thread.sleep(Long.parseLong(delayMs));
            return doc;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

}
