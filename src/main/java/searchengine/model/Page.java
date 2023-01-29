package searchengine.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
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
@Getter
@Setter
@RequiredArgsConstructor
@Entity
@Table(indexes = @Index(name = "path_index", columnList = "path"))
public class Page {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(nullable = false, columnDefinition = "VARCHAR(768)")
    private String path;

    @Column(nullable = false)
    private int code;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    public static Page constructPage(String path, Site site, Document doc) {
        Page page = new Page();
        page.setSite(site);
        page.setPath(path);
        if (doc == null || pageCodeNotValid(doc.connection().response().statusCode())) {
            log.debug("Проблема с найденной страницей - " + site.getUrl() + path);
            page.setContent("");
            page.setCode(doc == null ? HttpStatus.INTERNAL_SERVER_ERROR.value() : doc.connection().response().statusCode());
        } else {
            page.setContent(doc.toString());
            page.setCode(doc.connection().response().statusCode());
        }
        return page;
    }

    public static boolean pageCodeNotValid(int code) {
        return String.valueOf(code).substring(0, 1).matches("[4,5]");
    }

    @Override
    public String toString() {
        return "Page{" +
                "id=" + id +
                ", path='" + path + '\'' +
                ", code=" + code +
                '}';
    }

}