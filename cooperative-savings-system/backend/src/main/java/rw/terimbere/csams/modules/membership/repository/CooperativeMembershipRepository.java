package rw.terimbere.csams.modules.membership.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;

public interface CooperativeMembershipRepository extends JpaRepository<CooperativeMembership, UUID> {

    List<CooperativeMembership> findByUserIdAndMembershipStatus(UUID userId, String membershipStatus);

    Optional<CooperativeMembership> findByCooperativeIdAndUserId(UUID cooperativeId, UUID userId);

    /** Every membership of the scheme, whatever its status (a member who left still has history). */
    List<CooperativeMembership> findByCooperativeId(UUID cooperativeId);

    boolean existsByCooperativeIdAndUserId(UUID cooperativeId, UUID userId);

    long countByCooperativeIdAndMembershipStatus(UUID cooperativeId, String membershipStatus);

    long countByCooperativeId(UUID cooperativeId);

    long countByMembershipStatus(String membershipStatus);

    List<CooperativeMembership> findByCooperativeIdAndMembershipStatus(UUID cooperativeId, String membershipStatus);

    @Query(
            """
            SELECT m FROM CooperativeMembership m
            WHERE m.cooperativeId = :cooperativeId
              AND UPPER(m.membershipStatus) = 'ACTIVE'
            """)
    List<CooperativeMembership> findActiveByCooperativeId(@Param("cooperativeId") UUID cooperativeId);

    @Query(
            """
            SELECT COALESCE(SUM(m.shareCount), 0)
            FROM CooperativeMembership m
            WHERE m.cooperativeId = :cooperativeId
              AND UPPER(m.membershipStatus) = 'ACTIVE'
            """)
    Number sumShareCountByCooperativeIdAndActiveStatus(@Param("cooperativeId") UUID cooperativeId);

    List<CooperativeMembership> findByUserIdAndMembershipStatusIn(UUID userId, Collection<String> statuses);

    @Query(
            """
            SELECT m FROM CooperativeMembership m
            WHERE m.cooperativeId = :cooperativeId
              AND (:status IS NULL OR m.membershipStatus = :status)
              AND (
                   :q IS NULL OR :q = ''
                   OR EXISTS (
                       SELECT 1 FROM User u
                       WHERE u.id = m.userId
                         AND u.deleted = false
                         AND (
                              LOWER(u.username) LIKE LOWER(CONCAT('%', :q, '%'))
                           OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%'))
                           OR LOWER(u.firstName) LIKE LOWER(CONCAT('%', :q, '%'))
                           OR LOWER(u.lastName) LIKE LOWER(CONCAT('%', :q, '%'))
                           OR LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE LOWER(CONCAT('%', :q, '%'))
                         )
                   )
              )
            """)
    Page<CooperativeMembership> searchByCooperative(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("q") String q,
            @Param("status") String status,
            Pageable pageable);
}
