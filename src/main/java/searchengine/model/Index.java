package searchengine.model;

import lombok.Data;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

@Data
@Entity
@Table(name = "`index`")
public class Index {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "`rank`", nullable = false, unique = false, columnDefinition = "FLOAT")
    private double rank;

    @ManyToOne(cascade = CascadeType.PERSIST, optional = false)
    @JoinColumn(name = "page_id", nullable = false)
    private Page page;

    @ManyToOne(cascade = CascadeType.PERSIST, optional = false)
    @JoinColumn(name = "lemma_id", nullable = false)
    private Lemma lemma;

    @Override
    public String toString() {
        return "Index{" +
                "id=" + id +
                ", rank=" + rank +
                ", page=" + page +
                ", lemma=" + lemma +
                '}';
    }
}
