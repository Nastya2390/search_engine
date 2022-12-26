package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.Lemma;
import searchengine.model.Site;

import java.util.Optional;

@Repository
public interface LemmaRepository extends JpaRepository<Lemma, Integer> {

    Optional<Lemma> getLemmaById(Integer lemmaId);

    Integer countLemmaBySitesIs(Site site);

}
