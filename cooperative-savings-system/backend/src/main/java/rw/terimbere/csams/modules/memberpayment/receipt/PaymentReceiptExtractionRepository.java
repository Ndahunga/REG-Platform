package rw.terimbere.csams.modules.memberpayment.receipt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentReceiptExtractionRepository extends JpaRepository<PaymentReceiptExtraction, UUID> {

    Optional<PaymentReceiptExtraction> findByPaymentIdAndFileSha256(UUID paymentId, String fileSha256);

    Optional<PaymentReceiptExtraction> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    List<PaymentReceiptExtraction> findByCooperativeIdAndPaymentIdOrderByCreatedAtAsc(UUID cooperativeId, UUID paymentId);

    /** The same receipt content used on another payment of the scheme. */
    Optional<PaymentReceiptExtraction> findFirstByCooperativeIdAndFileSha256AndPaymentIdNotOrderByCreatedAtAsc(
            UUID cooperativeId, String fileSha256, UUID paymentId);
}
