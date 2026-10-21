package rw.terimbere.csams.modules.membercredit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberCreditEntryRepository extends JpaRepository<MemberCreditEntry, UUID> {

    List<MemberCreditEntry> findByCooperativeIdAndMemberUserIdOrderByCreatedAtAscIdAsc(UUID cooperativeId, UUID memberUserId);

    List<MemberCreditEntry> findByCooperativeIdOrderByCreatedAtAscIdAsc(UUID cooperativeId);

    Optional<MemberCreditEntry> findByCooperativeIdAndIdempotencyKey(UUID cooperativeId, String idempotencyKey);

    Optional<MemberCreditEntry> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    Optional<MemberCreditEntry> findBySourceAllocationId(UUID sourceAllocationId);

    boolean existsByReversesEntryId(UUID reversesEntryId);

    List<MemberCreditEntry> findBySourcePaymentIdAndCooperativeId(UUID sourcePaymentId, UUID cooperativeId);

    @Query("select coalesce(sum(e.amount), 0) from MemberCreditEntry e"
            + " where e.cooperativeId = :cooperativeId and e.entryType = :type")
    BigDecimal sumByType(@Param("cooperativeId") UUID cooperativeId, @Param("type") CreditEntryType type);

    /** Credit applied to the contributions bound to one rotation period (verified money that reached them). */
    @Query("select e from MemberCreditEntry e where e.cooperativeId = :cooperativeId"
            + " and e.entryType = rw.terimbere.csams.modules.membercredit.CreditEntryType.APPLIED"
            + " and e.appliedContributionId in (select c.id from Contribution c"
            + " where c.cooperativeId = :cooperativeId and c.rotationPeriodId = :periodId)")
    List<MemberCreditEntry> findAppliedToPeriod(@Param("cooperativeId") UUID cooperativeId, @Param("periodId") UUID periodId);

    /** Credit applied to any rotation-bound contribution of the scheme. */
    @Query("select e from MemberCreditEntry e where e.cooperativeId = :cooperativeId"
            + " and e.entryType = rw.terimbere.csams.modules.membercredit.CreditEntryType.APPLIED"
            + " and e.appliedContributionId in (select c.id from Contribution c"
            + " where c.cooperativeId = :cooperativeId and c.rotationPeriodId is not null)")
    List<MemberCreditEntry> findAppliedToRotationBound(@Param("cooperativeId") UUID cooperativeId);
}
