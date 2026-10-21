package rw.terimbere.csams.modules.memberpayment.verification;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentVerificationCheckRepository extends JpaRepository<PaymentVerificationCheck, UUID> {

    Optional<PaymentVerificationCheck> findFirstByCooperativeIdAndPaymentIdOrderByCreatedAtDesc(
            UUID cooperativeId, UUID paymentId);

    List<PaymentVerificationCheck> findByCooperativeIdAndPaymentIdOrderByCreatedAtAsc(UUID cooperativeId, UUID paymentId);

    /** The current (latest) check of every payment of the scheme whose current outcome is one of {@code outcomes}. */
    @Query("select c from PaymentVerificationCheck c where c.cooperativeId = :coop and c.outcome in :outcomes"
            + " and c.createdAt = (select max(c2.createdAt) from PaymentVerificationCheck c2"
            + " where c2.paymentId = c.paymentId and c2.cooperativeId = c.cooperativeId)"
            + " order by c.createdAt desc")
    List<PaymentVerificationCheck> currentWithOutcome(
            @Param("coop") UUID cooperativeId, @Param("outcomes") Collection<VerificationOutcome> outcomes);
}
