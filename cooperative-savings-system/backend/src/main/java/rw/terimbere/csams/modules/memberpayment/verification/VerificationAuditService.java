package rw.terimbere.csams.modules.memberpayment.verification;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.shared.auditing.AuditableAction;

/**
 * Records that a verification or posting attempt was refused, in its own transaction.
 *
 * <p>A refusal rolls the request back, and with it anything written inside that request. This service commits
 * independently, so the attempt (who, which payment, at which stage, and the reason code) survives the rollback. It
 * never changes a payment's status or any financial record, and it deliberately stores only the reason code: no
 * evidence basis, reference, receipt text or amount.
 */
@Service
@RequiredArgsConstructor
public class VerificationAuditService {

    private final AuditService auditService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void blocked(UUID actorId, UUID cooperativeId, UUID paymentId, String stage, String reasonCode) {
        auditService.record(
                actorId,
                cooperativeId,
                AuditableAction.PAYMENT_VERIFICATION_BLOCKED,
                "MemberPayment",
                paymentId,
                null,
                "{\"stage\":\"" + stage + "\",\"reason\":\"" + reasonCode + "\"}",
                null,
                null);
    }
}
