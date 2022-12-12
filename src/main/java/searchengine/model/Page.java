package searchengine.model;

import lombok.Data;

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

@Data
@Entity
@Table(indexes = {@Index(name = "path_index", columnList = "path")})
public class Page {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(nullable = false, unique = false, columnDefinition = "VARCHAR(50)")
    private String path;

    @Column(nullable = false, unique = false)
    private int code;

    @Column(nullable = false, unique = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "site_id", referencedColumnName = "id", insertable = false, updatable = false, nullable = false)
    private Site site;

}