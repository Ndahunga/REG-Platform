package rw.terimbere.csams.modules.rotation.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.rotation.entity.RotationConfiguration;

public interface RotationConfigurationRepository extends JpaRepository<RotationConfiguration, UUID> {

    List<RotationConfiguration> findByCooperativeIdOrderByEffectiveYearDescEffectiveMonthDesc(UUID cooperativeId);

    Optional<RotationConfiguration> findByCooperativeIdAndEffectiveYearAndEffectiveMonth(
            UUID cooperativeId, int effectiveYear, int effectiveMonth);

    /** Configurations in force for a period, newest effective month first; callers take the first. */
    @Query(
            """
            SELECT c FROM RotationConfiguration c
            WHERE c.cooperativeId = :cooperativeId
              AND (c.effectiveYear < :year OR (c.effectiveYear = :year AND c.effectiveMonth <= :month))
            ORDER BY c.effectiveYear DESC, c.effectiveMonth DESC
            """)
    List<RotationConfiguration> findEffectiveFor(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("year") int year,
            @Param("month") int month,
            Pageable pageable);
}
