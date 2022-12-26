package searchengine.model;

import lombok.Data;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.ManyToMany;
import javax.persistence.OneToMany;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(nullable = false, unique = false)
    @Enumerated(EnumType.STRING)
    private IndexingStatus status;

    @Column(nullable = false, unique = false)
    private LocalDateTime statusTime;

    @Column(nullable = true, unique = false, columnDefinition = "TEXT")
    private String lastError;

    @Column(nullable = false, unique = false, columnDefinition = "VARCHAR(255)")
    private String url;

    @Column(nullable = false, unique = false, columnDefinition = "VARCHAR(255)")
    private String name;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "site")
    private List<Page> pages;

    @ManyToMany(mappedBy = "sites")
    private List<Lemma> lemmas;

    @Override
    public String toString() {
        return "Site{" +
                "id=" + id +
                ", status=" + status +
                ", statusTime=" + statusTime +
                ", lastError='" + lastError + '\'' +
                ", url='" + url + '\'' +
                ", name='" + name + '\'' +
                ", pages=" + pages +
                '}';
    }
}
