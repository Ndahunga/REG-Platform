package rw.terimbere.csams.modules.socialfund.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.socialfund.entity.SocialDisbursement;
import rw.terimbere.csams.modules.socialfund.entity.SocialDisbursementStatus;

public interface SocialDisbursementRepository extends JpaRepository<SocialDisbursement, UUID> {

    Optional<SocialDisbursement> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    Page<SocialDisbursement> findByCooperativeId(UUID cooperativeId, Pageable pageable);

    Page<SocialDisbursement> findByCooperativeIdAndStatus(
            UUID cooperativeId, SocialDisbursementStatus status, Pageable pageable);

    Page<SocialDisbursement> findByCooperativeIdAndBeneficiaryMemberUserId(
            UUID cooperativeId, UUID beneficiaryMemberUserId, Pageable pageable);

    Page<SocialDisbursement> findByCooperativeIdAndBeneficiaryMemberUserIdAndStatus(
            UUID cooperativeId,
            UUID beneficiaryMemberUserId,
            SocialDisbursementStatus status,
            Pageable pageable);

    List<SocialDisbursement> findByCooperativeIdAndBeneficiaryMemberUserIdAndDisbursementDateAndAmount(
            UUID cooperativeId, UUID beneficiaryMemberUserId, LocalDate disbursementDate, BigDecimal amount);

    long countByCooperativeIdAndStatus(UUID cooperativeId, SocialDisbursementStatus status);

    @Query(
            """
            SELECT COALESCE(SUM(d.amount), 0)
            FROM SocialDisbursement d
            WHERE d.cooperativeId = :cooperativeId
              AND d.status = rw.terimbere.csams.modules.socialfund.entity.SocialDisbursementStatus.APPROVED
            """)
    BigDecimal sumApprovedAmount(@Param("cooperativeId") UUID cooperativeId);

    @Query(
            """
            SELECT d FROM SocialDisbursement d
            WHERE d.cooperativeId = :cooperativeId
              AND d.status = rw.terimbere.csams.modules.socialfund.entity.SocialDisbursementStatus.APPROVED
              AND d.disbursementDate >= :fromDate
              AND d.disbursementDate <= :toDate
            ORDER BY d.disbursementDate ASC, d.createdAt ASC
            """)
    List<SocialDisbursement> findApprovedInPeriod(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);
}
