package rw.terimbere.csams.modules.memberpayment.receipt;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptAnalysisRepository extends JpaRepository<ReceiptAnalysis, UUID> {

    Optional<ReceiptAnalysis> findByCooperativeIdAndUploadedByAndFileSha256(UUID cooperativeId, UUID uploadedBy, String fileSha256);

    Optional<ReceiptAnalysis> findByIdAndCooperativeId(UUID id, UUID cooperativeId);
}
