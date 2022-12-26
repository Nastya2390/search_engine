package searchengine.model;

import lombok.Data;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import java.util.List;

@Data
@Entity
public class Lemma {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(nullable = false, unique = true, columnDefinition = "VARCHAR(255)")
    private String lemma;

    @Column(nullable = false, unique = false)
    private int frequency;

    @ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(name = "lemma_sites",
            joinColumns = @JoinColumn(name = "lemma_id"),
            inverseJoinColumns = @JoinColumn(name = "site_id"))
    private List<Site> sites;

    @Override
    public String toString() {
        return "Lemma{" +
                "id=" + id +
                ", lemma='" + lemma + '\'' +
                ", frequency=" + frequency +
                ", sites=" + sites +
                '}';
    }
}
