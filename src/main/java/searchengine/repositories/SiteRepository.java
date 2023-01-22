package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.IndexingStatus;
import searchengine.model.Site;

import java.util.List;
import java.util.Optional;

@Repository
public interface SiteRepository extends JpaRepository<Site, Integer> {

    Optional<Site> getSiteByName(String name);

    Optional<Site> getSiteByUrl(String url);

    Optional<List<Site>> getSiteByStatus(IndexingStatus indexingStatus);

}
