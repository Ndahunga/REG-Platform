package rw.terimbere.csams.modules.reconciliation;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.memberpayment.dto.PaymentVerifyRequest;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;
import rw.terimbere.csams.modules.memberpayment.entity.VerificationMethod;
import rw.terimbere.csams.modules.memberpayment.service.MemberPaymentService;
import rw.terimbere.csams.modules.memberpayment.verification.TrustedTransactionService;
import rw.terimbere.csams.modules.memberpayment.verification.VerificationSource;
import rw.terimbere.csams.shared.exceptions.BusinessException;

/**
 * Verifies a member payment from the statement line it was reconciled with. The line becomes the trusted record of the
 * money received (recorded as imported by the officer who uploaded the statement), and the payment is verified through
 * the normal workflow with that line as its evidence. The usual separation of duties holds: the officer who uploaded
 * the statement and the member who submitted the payment cannot verify it.
 */
@Component
@RequiredArgsConstructor
public class StatementVerification {

    private final JdbcTemplate jdbc;
    private final TrustedTransactionService trustedTransactions;
    private final MemberPaymentService paymentService;

    /** The verification reference that names the statement line used as evidence. */
    public static String reference(UUID lineId) {
        return "STMT-" + lineId;
    }

    /** One transaction per payment: when verification is refused, the trusted record of the line is not kept either. */
    @Transactional
    public void verify(UUID cooperativeId, UUID paymentId, UUID lineId) {
        Map<String, Object> p = jdbc.queryForMap("select status, payment_method, transaction_reference, amount, currency from member_payments"
                + " where id = ? and cooperative_id = ?", paymentId, cooperativeId);
        Map<String, Object> l = jdbc.queryForMap("select l.transaction_date, l.narration, l.line_number, s.source, s.uploaded_by, s.original_filename"
                + " from bank_statement_lines l join bank_statements s on s.id = l.statement_id where l.id = ?", lineId);
        PaymentMethod channel = PaymentMethod.valueOf((String) p.get("payment_method"));
        String reference = (String) p.get("transaction_reference");
        if (reference == null || reference.isBlank()) {
            throw new BusinessException("PAYMENT_HAS_NO_REFERENCE", "The payment has no transaction reference to record the statement line under");
        }
        VerificationSource source = "MOBILE_MONEY".equals(l.get("source"))
                ? VerificationSource.MOBILE_MONEY_STATEMENT_IMPORT : VerificationSource.BANK_STATEMENT_IMPORT;
        java.time.LocalDate date = ReconciliationService.day(l.get("transaction_date"));
        TrustedTransactionService.Outcome recorded = trustedTransactions.recordFromStatement(cooperativeId, source, channel, reference,
                new BigDecimal(p.get("amount").toString()), (String) p.get("currency"),
                date.atStartOfDay(ZoneId.of("Africa/Kigali")).toInstant(), (UUID) l.get("uploaded_by"));
        if (recorded.result() == TrustedTransactionService.Result.CONFLICT || recorded.result() == TrustedTransactionService.Result.INVALID) {
            throw new BusinessException("STATEMENT_EVIDENCE_CONFLICT", "A different record of this transaction reference already exists ("
                    + recorded.reason() + ")");
        }
        if ("SUBMITTED".equals(p.get("status"))) {
            paymentService.startReview(cooperativeId, paymentId);
        }
        PaymentVerifyRequest evidence = new PaymentVerifyRequest();
        evidence.setMethod(source == VerificationSource.MOBILE_MONEY_STATEMENT_IMPORT
                ? VerificationMethod.MOBILE_MONEY_RECONCILED : VerificationMethod.BANK_RECONCILED);
        String narration = String.valueOf(l.get("narration"));
        evidence.setBasis(cut("Statement " + l.get("original_filename") + ", line " + l.get("line_number") + " of " + date + ": " + narration, 1000));
        evidence.setReference(reference(lineId));
        paymentService.verify(cooperativeId, paymentId, evidence);
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
