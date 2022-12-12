package searchengine.model;

import lombok.Data;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.OneToMany;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(nullable = false, unique = false, columnDefinition = "ENUM('INDEXING', 'INDEXED', 'FAILED')")
    private IndexingStatus status;

    @Column(nullable = false, unique = false)
    private LocalDateTime statusTime;

    @Column(nullable = true, unique = false, columnDefinition = "TEXT")
    private String lastError;

    @Column(nullable = false, unique = false, columnDefinition = "VARCHAR(255)")
    private String url;

    @Column(nullable = false, unique = false, columnDefinition = "VARCHAR(255)")
    private String name;

    @OneToMany(fetch = FetchType.EAGER, mappedBy = "site")
    private List<Page> pages;
}
