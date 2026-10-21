package rw.terimbere.csams.modules.ledger.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntry;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@Service
@RequiredArgsConstructor
public class LedgerService {

    public static final String SOURCE_CONTRIBUTION = "CONTRIBUTION";
    public static final String SOURCE_SPECIAL_CONTRIBUTION = "SPECIAL_CONTRIBUTION";
    public static final String SOURCE_LOAN = "LOAN";
    public static final String SOURCE_LOAN_REPAYMENT = "LOAN_REPAYMENT";
    public static final String SOURCE_FINE_PAYMENT = "FINE_PAYMENT";
    public static final String SOURCE_SOCIAL_CONTRIBUTION = "SOCIAL_CONTRIBUTION";
    public static final String SOURCE_SOCIAL_DISBURSEMENT = "SOCIAL_DISBURSEMENT";
    public static final String SOURCE_INVESTMENT = "INVESTMENT";
    public static final String SOURCE_INVESTMENT_RETURN = "INVESTMENT_RETURN";
    public static final String SOURCE_INCOME_EXPENSE = "INCOME_EXPENSE";
    public static final String SOURCE_MEMBER_PAYOUT = "MEMBER_PAYOUT";
    public static final String SOURCE_ROTATION_PAYOUT = "ROTATION_PAYOUT";
    public static final String SOURCE_SHARE_PURCHASE = "SHARE_PURCHASE";
    public static final String SOURCE_MEMBER_CREDIT = "MEMBER_CREDIT";

    private final LedgerEntryRepository ledgerEntryRepository;

    @Transactional
    public LedgerEntry appendApproved(AppendRequest request) {
        if (ledgerEntryRepository.existsByIdempotencyKey(request.getIdempotencyKey())) {
            return ledgerEntryRepository
                    .findByIdempotencyKey(request.getIdempotencyKey())
                    .orElseThrow(() -> new ConflictException("Ledger idempotency key already exists"));
        }

        BigDecimal debit = MoneyUtils.scaleForStorage(
                request.getDebitAmount() == null ? BigDecimal.ZERO : request.getDebitAmount());
        BigDecimal credit = MoneyUtils.scaleForStorage(
                request.getCreditAmount() == null ? BigDecimal.ZERO : request.getCreditAmount());
        MoneyUtils.assertNonNegative(debit);
        MoneyUtils.assertNonNegative(credit);
        if (MoneyUtils.isZero(debit) && MoneyUtils.isZero(credit)) {
            throw new IllegalArgumentException("Ledger entry must have a non-zero debit or credit");
        }
        // The rotating/retained split is all-or-nothing and always totals the entry's own amount: it classifies the
        // cash of the entry, it never adds to it.
        BigDecimal rotating = null;
        BigDecimal retained = null;
        if (request.getRotatingAmount() != null || request.getRetainedAmount() != null) {
            if (request.getRotatingAmount() == null || request.getRetainedAmount() == null) {
                throw new IllegalArgumentException("A ledger split needs both the rotating and the retained part");
            }
            rotating = MoneyUtils.scaleForStorage(request.getRotatingAmount());
            retained = MoneyUtils.scaleForStorage(request.getRetainedAmount());
            MoneyUtils.assertNonNegative(rotating);
            MoneyUtils.assertNonNegative(retained);
            if (rotating.add(retained).compareTo(debit.add(credit)) != 0) {
                throw new IllegalArgumentException("The rotating and retained parts must add up to the entry amount");
            }
        }

        LedgerEntry entry = LedgerEntry.builder()
                .cooperativeId(request.getCooperativeId())
                .memberUserId(request.getMemberUserId())
                .transactionType(request.getTransactionType())
                .debitAmount(debit)
                .creditAmount(credit)
                .currency(request.getCurrency() == null ? "RWF" : request.getCurrency())
                .transactionDate(
                        request.getTransactionDate() == null ? LocalDate.now() : request.getTransactionDate())
                .reference(request.getReference())
                .sourceEntityType(request.getSourceEntityType())
                .sourceEntityId(request.getSourceEntityId())
                .description(request.getDescription())
                .status(LedgerEntryStatus.APPROVED)
                .recordedBy(request.getRecordedBy())
                .approvedBy(request.getApprovedBy() == null ? request.getRecordedBy() : request.getApprovedBy())
                .reversesEntryId(request.getReversesEntryId())
                .idempotencyKey(request.getIdempotencyKey())
                .rotatingAmount(rotating)
                .retainedAmount(retained)
                .build();
        return ledgerEntryRepository.save(entry);
    }

    @Transactional(readOnly = true)
    public Optional<LedgerEntry> findLatestApproved(
            String sourceEntityType, UUID sourceEntityId, LedgerTransactionType transactionType) {
        return ledgerEntryRepository
                .findFirstBySourceEntityTypeAndSourceEntityIdAndTransactionTypeAndStatusOrderByCreatedAtDesc(
                        sourceEntityType, sourceEntityId, transactionType, LedgerEntryStatus.APPROVED);
    }

    /**
     * Marks the previous APPROVED credit entry as REVERSED and inserts a matching REVERSAL debit.
     * Returns the reversed entry if one existed.
     */
    @Transactional
    public Optional<LedgerEntry> reverseApprovedCredit(
            String sourceEntityType,
            UUID sourceEntityId,
            LedgerTransactionType originalType,
            String reversalIdempotencyKey,
            UUID recordedBy,
            String description) {
        Optional<LedgerEntry> existing =
                ledgerEntryRepository
                        .findFirstBySourceEntityTypeAndSourceEntityIdAndTransactionTypeAndStatusOrderByCreatedAtDesc(
                                sourceEntityType, sourceEntityId, originalType, LedgerEntryStatus.APPROVED);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        LedgerEntry prior = existing.get();
        prior.setStatus(LedgerEntryStatus.REVERSED);
        ledgerEntryRepository.save(prior);

        appendApproved(AppendRequest.builder()
                .cooperativeId(prior.getCooperativeId())
                .memberUserId(prior.getMemberUserId())
                .transactionType(LedgerTransactionType.REVERSAL)
                .debitAmount(prior.getCreditAmount())
                .creditAmount(BigDecimal.ZERO)
                .rotatingAmount(prior.getRotatingAmount())
                .retainedAmount(prior.getRetainedAmount())
                .currency(prior.getCurrency())
                .transactionDate(LocalDate.now())
                .reference(prior.getReference())
                .sourceEntityType(sourceEntityType)
                .sourceEntityId(sourceEntityId)
                .description(description == null ? "Reversal of " + prior.getIdempotencyKey() : description)
                .recordedBy(recordedBy)
                .approvedBy(recordedBy)
                .reversesEntryId(prior.getId())
                .idempotencyKey(reversalIdempotencyKey)
                .build());
        return Optional.of(prior);
    }

    public static String contributionKey(UUID contributionId, LedgerTransactionType type, int revision) {
        return SOURCE_CONTRIBUTION + ":" + contributionId + ":" + type.name() + ":v" + revision;
    }

    public static String contributionReversalKey(UUID contributionId, int revision) {
        return SOURCE_CONTRIBUTION + ":" + contributionId + ":REVERSAL:v" + revision;
    }

    public static String specialContributionKey(UUID specialContributionId) {
        return SOURCE_SPECIAL_CONTRIBUTION + ":" + specialContributionId + ":SPECIAL_CONTRIBUTION:v1";
    }

    public static String loanDisbursementKey(UUID loanId) {
        return SOURCE_LOAN + ":" + loanId + ":LOAN_DISBURSEMENT:v1";
    }

    public static String loanPrincipalRepaymentKey(UUID repaymentId) {
        return SOURCE_LOAN_REPAYMENT + ":" + repaymentId + ":LOAN_PRINCIPAL_REPAYMENT:v1";
    }

    public static String loanInterestRepaymentKey(UUID repaymentId) {
        return SOURCE_LOAN_REPAYMENT + ":" + repaymentId + ":LOAN_INTEREST_PAYMENT:v1";
    }

    public static String finePaymentKey(UUID finePaymentId) {
        return SOURCE_FINE_PAYMENT + ":" + finePaymentId + ":FINE_PAYMENT:v1";
    }

    public static String socialContributionKey(UUID socialContributionId) {
        return SOURCE_SOCIAL_CONTRIBUTION + ":" + socialContributionId + ":SOCIAL_CONTRIBUTION:v1";
    }

    public static String socialDisbursementKey(UUID socialDisbursementId) {
        return SOURCE_SOCIAL_DISBURSEMENT + ":" + socialDisbursementId + ":SOCIAL_DISBURSEMENT:v1";
    }

    public static String investmentOutflowKey(UUID investmentId) {
        return SOURCE_INVESTMENT + ":" + investmentId + ":INVESTMENT_OUTFLOW:v1";
    }

    public static String investmentCapitalReturnKey(UUID returnId) {
        return SOURCE_INVESTMENT_RETURN + ":" + returnId + ":INVESTMENT_CAPITAL_RETURN:v1";
    }

    public static String investmentProfitKey(UUID returnId) {
        return SOURCE_INVESTMENT_RETURN + ":" + returnId + ":INVESTMENT_PROFIT:v1";
    }

    public static String incomeExpenseKey(UUID transactionId, LedgerTransactionType type) {
        return SOURCE_INCOME_EXPENSE + ":" + transactionId + ":" + type.name() + ":v1";
    }

    public static String memberPayoutKey(UUID payoutLineId) {
        return SOURCE_MEMBER_PAYOUT + ":" + payoutLineId + ":MEMBER_PAYOUT:v1";
    }

    /** A confirmed rotation payout: a MEMBER_PAYOUT debit sourced from the payout, never from a share-out line. */
    public static String rotationPayoutKey(UUID rotationPayoutId) {
        return SOURCE_ROTATION_PAYOUT + ":" + rotationPayoutId + ":MEMBER_PAYOUT:v1";
    }

    /** Credit held for a member from one payment allocation (one receipt per allocation, ever). */
    public static String memberCreditReceivedKey(UUID allocationId) {
        return SOURCE_MEMBER_CREDIT + ":" + allocationId + ":MEMBER_CREDIT_RECEIVED:v1";
    }

    /** Credit applied by one officer request; the client key makes a repeated request a no-op. */
    public static String memberCreditAppliedKey(UUID cooperativeId, String idempotencyKey) {
        return SOURCE_MEMBER_CREDIT + ":" + cooperativeId + ":APPLIED:" + idempotencyKey;
    }

    public static String loanRepaymentReversalKey(UUID repaymentId, String component) {
        return SOURCE_LOAN_REPAYMENT + ":" + repaymentId + ":" + component + ":REVERSAL:v1";
    }

    public static String memberCreditReversedKey(UUID receiptEntryId) {
        return SOURCE_MEMBER_CREDIT + ":" + receiptEntryId + ":MEMBER_CREDIT_REVERSED:v1";
    }

    public static String sharePurchaseKey(UUID sharePurchaseId) {
        return SOURCE_SHARE_PURCHASE + ":" + sharePurchaseId + ":SHARE_PURCHASE:v1";
    }

    @Value
    @Builder
    public static class AppendRequest {
        UUID cooperativeId;
        UUID memberUserId;
        LedgerTransactionType transactionType;
        BigDecimal debitAmount;
        BigDecimal creditAmount;
        String currency;
        LocalDate transactionDate;
        String reference;
        String sourceEntityType;
        UUID sourceEntityId;
        String description;
        UUID recordedBy;
        UUID approvedBy;
        UUID reversesEntryId;
        String idempotencyKey;
        /** Of a contribution entry: the rotating and the retained part of its amount (both, or neither). */
        BigDecimal rotatingAmount;
        BigDecimal retainedAmount;
    }
}
