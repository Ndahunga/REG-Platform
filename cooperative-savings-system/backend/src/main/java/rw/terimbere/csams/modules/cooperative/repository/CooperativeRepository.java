package rw.terimbere.csams.modules.cooperative.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeStatus;
import rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel;

public interface CooperativeRepository extends JpaRepository<Cooperative, UUID> {

    Optional<Cooperative> findByIdAndDeletedFalse(UUID id);

    /** The scheme's operating model, or null for a legacy scheme that has none (and for an unknown id). */
    @Query("SELECT c.operatingModel FROM Cooperative c WHERE c.id = :id AND c.deleted = false")
    SchemeOperatingModel findOperatingModelById(@Param("id") UUID id);

    /**
     * The only way a model is ever recorded after creation: one atomic statement that matches only a scheme whose
     * model is still NULL. A concurrent or repeated call matches nothing (returns 0) instead of overwriting.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "UPDATE cooperatives SET operating_model = :model, operating_model_unclassified = FALSE, "
                    + "updated_at = CURRENT_TIMESTAMP, version = version + 1 "
                    + "WHERE id = :id AND operating_model IS NULL AND deleted = FALSE",
            nativeQuery = true)
    int classifyOperatingModelIfUnset(@Param("id") UUID id, @Param("model") String model);

    List<Cooperative> findAllByDeletedFalseAndStatus(CooperativeStatus status);

    long countByDeletedFalse();

    long countByDeletedFalseAndStatus(CooperativeStatus status);

    List<Cooperative> findByIdInAndDeletedFalse(Collection<UUID> ids);

    @Query(
            """
            SELECT c FROM Cooperative c
            WHERE c.deleted = false
              AND (:status IS NULL OR c.status = :status)
              AND (
                   :q IS NULL OR :q = ''
                   OR LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(c.registrationNumber, '')) LIKE LOWER(CONCAT('%', :q, '%'))
              )
              AND (:restrictIds = false OR c.id IN :ids)
            """)
    Page<Cooperative> search(
            @Param("q") String q,
            @Param("status") CooperativeStatus status,
            @Param("restrictIds") boolean restrictIds,
            @Param("ids") Collection<UUID> ids,
            Pageable pageable);

    boolean existsByRegistrationNumberIgnoreCaseAndDeletedFalseAndIdNot(String registrationNumber, UUID id);

    boolean existsByRegistrationNumberIgnoreCaseAndDeletedFalse(String registrationNumber);
}
