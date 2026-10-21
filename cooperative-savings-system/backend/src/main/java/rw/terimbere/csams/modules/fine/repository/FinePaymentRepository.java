package rw.terimbere.csams.modules.fine.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.fine.entity.FinePayment;
import rw.terimbere.csams.modules.fine.entity.FinePaymentStatus;

public interface FinePaymentRepository
        extends JpaRepository<FinePayment, UUID>, JpaSpecificationExecutor<FinePayment> {

    Optional<FinePayment> findByIdAndCooperativeIdAndFineId(UUID id, UUID cooperativeId, UUID fineId);

    List<FinePayment> findByFineIdAndCooperativeIdOrderByCreatedAtDesc(UUID fineId, UUID cooperativeId);

    List<FinePayment> findByCooperativeIdAndFineIdAndPaymentDateAndAmount(
            UUID cooperativeId, UUID fineId, LocalDate paymentDate, BigDecimal amount);

    long countByCooperativeIdAndStatus(UUID cooperativeId, FinePaymentStatus status);

    long countByStatus(FinePaymentStatus status);

    default List<FinePayment> findFiltered(
            UUID cooperativeId,
            UUID memberUserId,
            FinePaymentStatus status,
            LocalDate fromDate,
            LocalDate toDate) {
        return findAll(
                FinePaymentSpecs.filtered(cooperativeId, memberUserId, status, fromDate, toDate),
                Sort.by(Sort.Direction.DESC, "paymentDate").and(Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    default Page<FinePayment> findQueuePage(
            UUID cooperativeId,
            FinePaymentStatus status,
            LocalDate fromDate,
            LocalDate toDate,
            String q,
            Pageable pageable) {
        Pageable page = pageable == null ? Pageable.unpaged() : pageable;
        return findAll(FinePaymentSpecs.queue(cooperativeId, status, fromDate, toDate, q), page);
    }

    @Query(
            """
            SELECT COALESCE(SUM(p.amount), 0)
            FROM FinePayment p
            WHERE p.fineId = :fineId
              AND p.status = rw.terimbere.csams.modules.fine.entity.FinePaymentStatus.PENDING
            """)
    BigDecimal sumPendingAmountByFineId(@Param("fineId") UUID fineId);

    @Query(
            """
            SELECT COALESCE(SUM(p.amount), 0)
            FROM FinePayment p
            WHERE p.cooperativeId = :cooperativeId
              AND p.memberUserId = :memberUserId
              AND p.status = rw.terimbere.csams.modules.fine.entity.FinePaymentStatus.APPROVED
            """)
    BigDecimal sumApprovedAmountByMember(
            @Param("cooperativeId") UUID cooperativeId, @Param("memberUserId") UUID memberUserId);

    @Query(
            """
            SELECT COALESCE(SUM(p.amount), 0)
            FROM FinePayment p
            WHERE p.cooperativeId = :cooperativeId
              AND p.status = rw.terimbere.csams.modules.fine.entity.FinePaymentStatus.APPROVED
              AND p.paymentDate >= :fromDate
              AND p.paymentDate <= :toDate
            """)
    BigDecimal sumApprovedAmountInDateRange(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);
}
