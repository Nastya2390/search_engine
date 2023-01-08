package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.Index;
import searchengine.model.Lemma;

import java.util.List;
import java.util.Optional;

@Repository
public interface IndexRepository extends JpaRepository<Index, Integer> {

    Optional<List<Index>> getIndexByPageId(Integer pageId);

    Optional<List<Index>> getIndexByPageIdAndLemmaId(Integer pageId, Integer lemmaId);

    Optional<List<Index>> getIndexByLemma(Lemma lemma);
}
