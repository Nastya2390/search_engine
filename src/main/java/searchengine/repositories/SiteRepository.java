package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import searchengine.model.IndexingStatus;
import searchengine.model.Site;

import java.util.List;
import java.util.Optional;

@Repository
public interface SiteRepository extends JpaRepository<Site,Integer> {

    @Query("Select id from Site where name = :name")
    Optional<Integer> getIdBySiteName(String name);

    @Query("Select id from Site where status = :indexingStatus")
    Optional<List<Integer>> getIdByIndexingStatus(IndexingStatus indexingStatus);

    Optional<Site> getSiteById(Integer siteId);
}
