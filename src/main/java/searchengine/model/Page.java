package searchengine.model;

import lombok.Data;
import org.jsoup.nodes.Document;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

@Data
@Entity
//@Table(indexes = {@Index(name = "path_index", columnList = "path")})
public class Page {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(nullable = false, unique = false, columnDefinition = "TEXT")
    private String path;

    @Column(nullable = false, unique = false)
    private int code;

    @Column(nullable = false, unique = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    public static Page constructPage(String path, Site site, Document doc) {
        Page page = new Page();
        page.setSite(site);
        page.setPath(path);
        page.setContent(doc.toString());
        page.setCode(doc.connection().response().statusCode());
        return page;
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