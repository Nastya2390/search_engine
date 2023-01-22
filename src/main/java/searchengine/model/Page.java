package searchengine.model;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpStatus;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

@Slf4j
@Data
@Entity
@Table(indexes = @Index(name = "path_index", columnList = "path"))
public class Page {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(nullable = false, unique = false, columnDefinition = "VARCHAR(768)")
    private String path;

    @Column(nullable = false, unique = false)
    private int code;

    @Column(nullable = false, unique = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(nullable = false, unique = false, columnDefinition = "VARCHAR(255)")
    private String title;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    public static Page constructPage(String path, Site site, Document doc) {
        Page page = new Page();
        page.setSite(site);
        page.setPath(path);
        if (doc == null || !isPageCodeValid(doc.connection().response().statusCode())) {
            log.debug("Проблема с найденной страницей - " + site.getUrl() + path);
            page.setContent("");
            page.setTitle("");
            page.setCode(doc == null ? HttpStatus.INTERNAL_SERVER_ERROR.value() : doc.connection().response().statusCode());
        } else {
            page.setContent(doc.text());
            page.setTitle(doc.title());
            page.setCode(doc.connection().response().statusCode());
        }
        return page;
    }

    public static boolean isPageCodeValid(int code) {
        return !String.valueOf(code).substring(0, 1).matches("[4,5]");
    }

    @Override
    public String toString() {
        return "Page{" +
                "id=" + id +
                ", path='" + path + '\'' +
                ", code=" + code +
                ", site=" + site +
                '}';
    }

}