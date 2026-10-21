package rw.terimbere.csams.modules.memberpayment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.memberpayment.dto.MemberPaymentCreateCommand;
import rw.terimbere.csams.modules.membercredit.CreditPurpose;
import rw.terimbere.csams.modules.memberpayment.dto.MemberPaymentCreateCommand.Allocation;
import rw.terimbere.csams.modules.memberpayment.dto.MemberPaymentResponse;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPayment;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentEvent;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentStatus;
import rw.terimbere.csams.modules.memberpayment.entity.ReceiptStatus;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentAllocationRepository;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentEventRepository;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentRepository;
import rw.terimbere.csams.modules.memberpayment.dto.PaymentVerifyRequest;
import rw.terimbere.csams.modules.memberpayment.entity.VerificationMethod;
import rw.terimbere.csams.modules.memberpayment.service.MemberPaymentService;
import rw.terimbere.csams.modules.memberpayment.service.PaymentPostingService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;

/**
 * Payment foundation: creation and validation, the verified-before-posted state machine, duplicate and repeated
 * submission handling, tenant isolation, concurrency and the database-level safety nets of migration V28.
 *
 * <p>Runs the real Flyway migrations (V1..V28) on H2 in PostgreSQL mode, so every test also proves the migration
 * applies cleanly next to the existing schema.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberPaymentFoundationIntegrationTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MemberPaymentService service;
    @Autowired private PaymentPostingService postingService;
    @Autowired private MemberPaymentRepository paymentRepository;
    @Autowired private MemberPaymentAllocationRepository allocationRepository;
    @Autowired private MemberPaymentEventRepository eventRepository;
    @Autowired private LoanRepository loanRepository;
    @Autowired private ContributionRepository contributionRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID coopA;
    private UUID coopB;
    private UUID memberA1;
    private UUID memberA2;
    private UUID accountantA;
    private UUID accountantA2;
    private UUID memberB1;
    private Loan loanA1;
    private Loan loanA2;
    private Loan loanB1;
    private UserPrincipal member1;
    private UserPrincipal member2;
    private UserPrincipal accountant;
    private UserPrincipal accountant2;
    private UserPrincipal memberOfB;

    @BeforeAll
    void createFixtures() throws Exception {
        String token = loginAccessToken();
        coopA = createCooperative(token, "Payments A");
        coopB = createCooperative(token, "Payments B");
        memberA1 = registerMember(token, coopA, "MEMBER");
        memberA2 = registerMember(token, coopA, "MEMBER");
        accountantA = registerMember(token, coopA, "ACCOUNTANT");
        accountantA2 = registerMember(token, coopA, "ACCOUNTANT");
        memberB1 = registerMember(token, coopB, "MEMBER");
        loanA1 = saveLoan(coopA, memberA1, LoanStatus.ACTIVE);
        loanA2 = saveLoan(coopA, memberA2, LoanStatus.ACTIVE);
        loanB1 = saveLoan(coopB, memberB1, LoanStatus.ACTIVE);

        member1 = principal(memberA1, coopA, Set.of("MEMBER"), Set.of());
        member2 = principal(memberA2, coopA, Set.of("MEMBER"), Set.of());
        accountant = principal(accountantA, coopA, Set.of("ACCOUNTANT"), Set.of("CONTRIBUTION_WRITE", "LOAN_WRITE"));
        accountant2 = principal(accountantA2, coopA, Set.of("ACCOUNTANT"), Set.of("CONTRIBUTION_WRITE", "LOAN_WRITE"));
        memberOfB = principal(memberB1, coopB, Set.of("MEMBER"), Set.of());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------ creation

    @Test
    void createsASubmittedPaymentWithoutTouchingAnyFinancialRecord() {
        long ledgerBefore = ledgerEntryRepository.count();
        long contributionsBefore = contributionRepository.count();
        long loansBefore = loanRepository.count();
        BigDecimal outstandingBefore = loanRepository.findById(loanA1.getId()).orElseThrow().getOutstandingPrincipal();

        as(member1);
        MemberPaymentResponse created = service.create(coopA, command(
                "30000",
                PaymentMethod.MTN_MOMO,
                ref(),
                Allocation.contribution(2026, 9, money("10000")),
                Allocation.loanRepayment(loanA1.getId(), money("20000"))));

        assertThat(created.status()).isEqualTo(PaymentStatus.SUBMITTED);
        assertThat(created.memberUserId()).isEqualTo(memberA1);
        assertThat(created.cooperativeId()).isEqualTo(coopA);
        assertThat(created.amount()).isEqualByComparingTo("30000");
        assertThat(created.allocatedAmount()).isEqualByComparingTo("30000");
        assertThat(created.currency()).isEqualTo("RWF");
        assertThat(created.allocations()).hasSize(2);
        // Receipt analysis is optional and absent at creation.
        assertThat(created.receiptStatus()).isNull();
        assertThat(created.extractedAmount()).isNull();
        assertThat(created.receiptConfidence()).isNull();
        assertThat(eventsOf(created.id())).extracting(MemberPaymentEvent::getToStatus).containsExactly(PaymentStatus.SUBMITTED);

        // A payment is a claim, not a transaction.
        assertThat(ledgerEntryRepository.count()).isEqualTo(ledgerBefore);
        assertThat(contributionRepository.count()).isEqualTo(contributionsBefore);
        assertThat(loanRepository.count()).isEqualTo(loansBefore);
        assertThat(loanRepository.findById(loanA1.getId()).orElseThrow().getOutstandingPrincipal())
                .isEqualByComparingTo(outstandingBefore);
    }

    @Test
    void rejectsMissingOrInvalidRequiredFields() {
        as(member1);
        Allocation oneContribution = Allocation.contribution(2026, 9, money("1000"));
        assertCode(() -> service.create(coopA, base(null, PaymentMethod.MTN_MOMO, ref(), List.of(oneContribution))), "INVALID_AMOUNT");
        assertCode(() -> service.create(coopA, command("0", PaymentMethod.MTN_MOMO, ref(), Allocation.contribution(2026, 9, money("1")))), "INVALID_AMOUNT");
        assertCode(() -> service.create(coopA, command("-5", PaymentMethod.MTN_MOMO, ref(), Allocation.contribution(2026, 9, money("1")))), "INVALID_AMOUNT");
        assertCode(() -> service.create(coopA, command("10.00001", PaymentMethod.MTN_MOMO, ref(), Allocation.contribution(2026, 9, money("10.00001")))), "INVALID_AMOUNT");
        assertCode(() -> service.create(coopA, command("1000", null, ref(), Allocation.contribution(2026, 9, money("1000")))), "INVALID_PAYMENT");
        assertCode(() -> service.create(coopA, command("1000", PaymentMethod.MTN_MOMO, null, Allocation.contribution(2026, 9, money("1000")))), "PAYMENT_REFERENCE_REQUIRED");
        assertCode(() -> service.create(coopA, base(money("1000"), PaymentMethod.MTN_MOMO, ref(), List.of())), "INVALID_ALLOCATION");
        assertCode(() -> service.create(coopA, new MemberPaymentCreateCommand(
                null, money("1000"), LocalDate.now(KIGALI).plusDays(1), PaymentMethod.MTN_MOMO, ref(), null, null, null,
                List.of(oneContribution))), "PAYMENT_DATE_IN_FUTURE");
        assertCode(() -> service.create(coopA, new MemberPaymentCreateCommand(
                null, money("1000"), null, PaymentMethod.MTN_MOMO, ref(), null, null, null, List.of(oneContribution))), "INVALID_PAYMENT");
    }

    @Test
    void cashPaymentsMayOmitAReference() {
        as(member1);
        MemberPaymentResponse cash = service.create(coopA, command(
                "5000", PaymentMethod.CASH, null, Allocation.contribution(2026, 10, money("5000"))));
        assertThat(cash.status()).isEqualTo(PaymentStatus.SUBMITTED);
        assertThat(cash.transactionReference()).isNull();
    }

    @Test
    void allocationsMustReconcileWithThePaymentAmount() {
        as(member1);
        assertCode(() -> service.create(coopA, command("30000", PaymentMethod.MTN_MOMO, ref(),
                Allocation.contribution(2026, 9, money("10000")),
                Allocation.loanRepayment(loanA1.getId(), money("19999")))), "ALLOCATION_TOTAL_MISMATCH");
        assertCode(() -> service.create(coopA, command("30000", PaymentMethod.MTN_MOMO, ref(),
                Allocation.contribution(2026, 9, money("10000")),
                Allocation.loanRepayment(loanA1.getId(), money("20001")))), "ALLOCATION_TOTAL_MISMATCH");
    }

    @Test
    void duplicateAllocationTargetsAndBadShapesAreRejected() {
        as(member1);
        assertCode(() -> service.create(coopA, command("2000", PaymentMethod.MTN_MOMO, ref(),
                Allocation.contribution(2026, 9, money("1000")),
                Allocation.contribution(2026, 9, money("1000")))), "INVALID_ALLOCATION");
        assertCode(() -> service.create(coopA, command("2000", PaymentMethod.MTN_MOMO, ref(),
                Allocation.loanRepayment(loanA1.getId(), money("1000")),
                Allocation.loanRepayment(loanA1.getId(), money("1000")))), "INVALID_ALLOCATION");
        assertCode(() -> service.create(coopA, command("1000", PaymentMethod.MTN_MOMO, ref(),
                Allocation.contribution(2026, 13, money("1000")))), "INVALID_ALLOCATION");
        assertCode(() -> service.create(coopA, command("1000", PaymentMethod.MTN_MOMO, ref(),
                new Allocation(rw.terimbere.csams.modules.memberpayment.entity.PaymentAllocationType.LOAN_REPAYMENT,
                        money("1000"), null, null, null, null, null, null))), "INVALID_ALLOCATION");
    }

    @Test
    void memberCreditNeedsAnExplicitPurposeAndAPrepaymentNeedsItsLoan() {
        as(member1);
        // excess money is never absorbed silently: it must say what it is held for
        assertCode(() -> service.create(coopA, command("7000", PaymentMethod.MTN_MOMO, ref(),
                Allocation.contribution(2026, 11, money("5000")), Allocation.memberCredit(money("2000"), null, null))),
                "INVALID_ALLOCATION");
        assertCode(() -> service.create(coopA, command("2000", PaymentMethod.MTN_MOMO, ref(),
                Allocation.memberCredit(money("2000"), CreditPurpose.LOAN_PREPAYMENT, null))),
                "INVALID_ALLOCATION");
        assertCode(() -> service.create(coopA, command("2000", PaymentMethod.MTN_MOMO, ref(),
                Allocation.memberCredit(money("2000"), CreditPurpose.ADVANCE_CONTRIBUTION, UUID.randomUUID()))),
                "INVALID_ALLOCATION");
    }

    // ------------------------------------------------- loan repayment breakdown

    @Test
    void breakdownAtCreationMustReconcileAndBeComplete() {
        as(member1);
        assertCode(() -> service.create(coopA, command("10000", PaymentMethod.MTN_MOMO, ref(),
                Allocation.loanRepayment(loanA1.getId(), money("10000"), money("6000"), money("3000"), money("999")))), "INVALID_BREAKDOWN");
        assertCode(() -> service.create(coopA, command("10000", PaymentMethod.MTN_MOMO, ref(),
                Allocation.loanRepayment(loanA1.getId(), money("10000"), money("6000"), null, money("4000")))), "INVALID_BREAKDOWN");
        assertCode(() -> service.create(coopA, command("10000", PaymentMethod.MTN_MOMO, ref(),
                Allocation.loanRepayment(loanA1.getId(), money("10000"), money("-1000"), money("10000"), money("1000")))), "INVALID_AMOUNT");

        MemberPaymentResponse ok = service.create(coopA, command("10000", PaymentMethod.MTN_MOMO, ref(),
                Allocation.loanRepayment(loanA1.getId(), money("10000"), money("6000"), money("3000"), money("1000"))));
        assertThat(ok.allocations().get(0).principalAmount()).isEqualByComparingTo("6000");
        assertThat(ok.allocations().get(0).penaltyAmount()).isEqualByComparingTo("1000");
    }

    @Test
    void breakdownIsOptionalBeforeVerificationButMustReconcileWhenRecorded() {
        as(member1);
        MemberPaymentResponse created = service.create(coopA, command("10000", PaymentMethod.MTN_MOMO, ref(),
                Allocation.loanRepayment(loanA1.getId(), money("10000"))));
        UUID allocationId = created.allocations().get(0).id();

        as(accountant);
        service.startReview(coopA, created.id());

        as(accountant2);
        assertCode(() -> service.recordLoanBreakdown(coopA, created.id(), allocationId, money("5000"), money("4000"), money("500")), "INVALID_BREAKDOWN");
        service.recordLoanBreakdown(coopA, created.id(), allocationId, money("7000"), money("2500"), money("500"));
        MemberPaymentResponse verified = service.verify(coopA, created.id(), evidence());
        assertThat(verified.status()).isEqualTo(PaymentStatus.VERIFIED);
        assertThat(verified.allocations().get(0).interestAmount()).isEqualByComparingTo("2500");
        assertThat(service.verify(coopA, created.id(), evidence()).status()).isEqualTo(PaymentStatus.VERIFIED);

        // After verification the split is frozen.
        assertCode(() -> service.recordLoanBreakdown(coopA, created.id(), allocationId, money("10000"), money("0"), money("0")), "PAYMENT_NOT_EDITABLE");
    }

    @Test
    void breakdownCanOnlyBeSetOnLoanAllocations() {
        as(member1);
        MemberPaymentResponse created = service.create(coopA, command("1000", PaymentMethod.MTN_MOMO, ref(),
                Allocation.contribution(2026, 12, money("1000"))));
        as(accountant);
        assertCode(() -> service.recordLoanBreakdown(
                coopA, created.id(), created.allocations().get(0).id(), money("1000"), money("0"), money("0")), "INVALID_BREAKDOWN");
    }

    // ------------------------------------------------------- state machine

    @Test
    void walksSubmissionToVerificationWithAFullHistory() {
        as(member1);
        MemberPaymentResponse created = service.create(coopA, command("4000", PaymentMethod.MTN_MOMO, ref(),
                Allocation.contribution(2027, 1, money("4000"))));
        UUID id = created.id();

        as(accountant);
        assertThat(service.startReview(coopA, id).status()).isEqualTo(PaymentStatus.UNDER_REVIEW);
        as(accountant2);
        MemberPaymentResponse verified = service.verify(coopA, id, evidence());
        assertThat(verified.status()).isEqualTo(PaymentStatus.VERIFIED);
        assertThat(verified.verifiedBy()).isEqualTo(accountantA2);
        MemberPayment stored = paymentRepository.findById(id).orElseThrow();
        assertThat(stored.getVerificationMethod()).isEqualTo(VerificationMethod.PROVIDER_CONFIRMED);
        assertThat(stored.getVerificationReference()).isEqualTo("PROVIDER-REF-1");

        assertThat(eventsOf(id))
                .extracting(MemberPaymentEvent::getToStatus)
                .containsExactly(PaymentStatus.SUBMITTED, PaymentStatus.UNDER_REVIEW, PaymentStatus.VERIFIED);
        assertThat(eventsOf(id)).extracting(MemberPaymentEvent::getFromStatus).containsExactly(
                null, PaymentStatus.SUBMITTED, PaymentStatus.UNDER_REVIEW);
    }

    @Test
    void rejectsInvalidTransitions() {
        UUID id = submittedContribution(2027, 2, "3000");
        as(accountant);
        assertCode(() -> service.verify(coopA, id, evidence()), "INVALID_PAYMENT_TRANSITION");
        assertCode(() -> postingService.post(coopA, id, null), "PAYMENT_NOT_VERIFIED");

        service.startReview(coopA, id);
        assertCode(() -> service.startReview(coopA, id), "INVALID_PAYMENT_TRANSITION");
        assertCode(() -> postingService.post(coopA, id, null), "PAYMENT_NOT_VERIFIED");

        MemberPaymentResponse rejected = service.reject(coopA, id, "Receipt unreadable");
        assertThat(rejected.status()).isEqualTo(PaymentStatus.REJECTED);
        as(accountant2);
        assertCode(() -> service.verify(coopA, id, evidence()), "INVALID_PAYMENT_TRANSITION");
        assertCode(() -> service.reject(coopA, id, "again"), "INVALID_PAYMENT_TRANSITION");
    }

    @Test
    void anUnverifiedPaymentCanNeverBePosted() {
        UUID id = submittedContribution(2027, 3, "3000");
        as(accountant);
        assertCode(() -> postingService.post(coopA, id, null), "PAYMENT_NOT_VERIFIED");
        service.startReview(coopA, id);
        assertCode(() -> postingService.post(coopA, id, null), "PAYMENT_NOT_VERIFIED");
        assertThat(paymentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.UNDER_REVIEW);
        assertThat(paymentRepository.findById(id).orElseThrow().getPostedAt()).isNull();
    }

    @Test
    void receiptClassificationNeverAuthorizesAnything() {
        UUID id = submittedContribution(2027, 6, "3000");
        MemberPayment payment = paymentRepository.findById(id).orElseThrow();
        payment.setReceiptStatus(ReceiptStatus.SUCCESS_INDICATED);
        payment.setReceiptConfidence(new BigDecimal("0.9900"));
        payment.setExtractedAmount(new BigDecimal("3000.0000"));
        paymentRepository.saveAndFlush(payment);

        as(accountant);
        assertCode(() -> service.verify(coopA, id, evidence()), "INVALID_PAYMENT_TRANSITION");
        assertCode(() -> postingService.post(coopA, id, null), "PAYMENT_NOT_VERIFIED");
        assertThat(paymentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUBMITTED);
    }

    @Test
    void rejectionAndReversalNeedAReason() {
        UUID id = submittedContribution(2027, 7, "3000");
        as(accountant);
        assertCode(() -> service.reject(coopA, id, "  "), "REASON_REQUIRED");
        assertCode(() -> service.reject(coopA, id, null), "REASON_REQUIRED");
        assertThat(paymentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUBMITTED);
    }

    // ------------------------------------------- duplicates and repetition

    @Test
    void duplicateReferenceInTheSameSchemeAndMethodIsRefusedWhateverTheFormatting() {
        String reference = "MP" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        as(member1);
        service.create(coopA, command("1000", PaymentMethod.MTN_MOMO, reference, Allocation.contribution(2027, 8, money("1000"))));

        as(member2);
        assertThatThrownBy(() -> service.create(coopA, command(
                        "1000", PaymentMethod.MTN_MOMO, " " + reference.toLowerCase() + "- ",
                        Allocation.contribution(2027, 8, money("1000")))))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getCode())
                .isEqualTo("DUPLICATE_PAYMENT_REFERENCE");
    }

    @Test
    void sameReferenceUnderAnotherMethodOrAnotherSchemeIsNotADuplicate() {
        String reference = ref();
        as(member1);
        service.create(coopA, command("1000", PaymentMethod.MTN_MOMO, reference, Allocation.contribution(2027, 9, money("1000"))));
        // Different provider: not the same transaction.
        MemberPaymentResponse bank = service.create(coopA, command(
                "1000", PaymentMethod.BANK_TRANSFER, reference, Allocation.contribution(2027, 10, money("1000"))));
        assertThat(bank.status()).isEqualTo(PaymentStatus.SUBMITTED);

        // Different Saving Scheme: references are never shared across schemes.
        as(memberOfB);
        MemberPaymentResponse other = service.create(coopB, command(
                "1000", PaymentMethod.MTN_MOMO, reference, Allocation.contribution(2027, 9, money("1000"))));
        assertThat(other.cooperativeId()).isEqualTo(coopB);
    }

    @Test
    void aRejectedPaymentReleasesItsReferenceButOtherStatesKeepIt() {
        String rejectedRef = ref();
        as(member1);
        MemberPaymentResponse first = service.create(coopA, command(
                "1000", PaymentMethod.MTN_MOMO, rejectedRef, Allocation.contribution(2027, 11, money("1000"))));
        as(accountant);
        service.reject(coopA, first.id(), "Wrong amount on receipt");
        assertThat(paymentRepository.findById(first.id()).orElseThrow().getReferenceKey()).isNull();

        // A corrected resubmission may reuse the reference.
        as(member1);
        MemberPaymentResponse again = service.create(coopA, command(
                "1000", PaymentMethod.MTN_MOMO, rejectedRef, Allocation.contribution(2027, 11, money("1000"))));
        assertThat(again.status()).isEqualTo(PaymentStatus.SUBMITTED);

        // A posted payment keeps holding its reference...
        String postedRef = ref();
        UUID posted = verifiedContribution(2027, 12, "1000", postedRef);
        forceStatus(posted, "POSTED");
        as(member2);
        assertThatThrownBy(() -> service.create(coopA, command(
                        "1000", PaymentMethod.MTN_MOMO, postedRef, Allocation.contribution(2028, 1, money("1000")))))
                .isInstanceOf(ConflictException.class);

        // ...and so does a reversed one: an already-posted receipt cannot be silently reused.
        forceStatus(posted, "REVERSED");
        as(member2);
        assertThatThrownBy(() -> service.create(coopA, command(
                        "1000", PaymentMethod.MTN_MOMO, postedRef, Allocation.contribution(2028, 1, money("1000")))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void repeatedSubmissionWithTheSameIdempotencyKeyReturnsTheOriginal() {
        String key = "idem-" + UUID.randomUUID();
        String reference = ref();
        as(member1);
        MemberPaymentCreateCommand cmd = withKey(key, command(
                "2500", PaymentMethod.MTN_MOMO, reference, Allocation.contribution(2028, 2, money("2500"))));
        MemberPaymentResponse first = service.create(coopA, cmd);
        MemberPaymentResponse second = service.create(coopA, cmd);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(eventsOf(first.id())).hasSize(1);
        assertThat(paymentRepository.findAll().stream().filter(p -> reference.equals(p.getTransactionReference()))).hasSize(1);
    }

    @Test
    void reusingAnIdempotencyKeyForADifferentPaymentIsAConflict() {
        String key = "idem-" + UUID.randomUUID();
        as(member1);
        service.create(coopA, withKey(key, command(
                "2500", PaymentMethod.MTN_MOMO, ref(), Allocation.contribution(2028, 3, money("2500")))));
        assertThatThrownBy(() -> service.create(coopA, withKey(key, command(
                        "9999", PaymentMethod.MTN_MOMO, ref(), Allocation.contribution(2028, 4, money("9999"))))))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getCode())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void idempotencyKeysAreScopedPerSubmitter() {
        String key = "idem-" + UUID.randomUUID();
        as(member1);
        MemberPaymentResponse a = service.create(coopA, withKey(key, command(
                "1000", PaymentMethod.MTN_MOMO, ref(), Allocation.contribution(2028, 5, money("1000")))));
        as(member2);
        MemberPaymentResponse b = service.create(coopA, withKey(key, command(
                "1000", PaymentMethod.MTN_MOMO, ref(), Allocation.contribution(2028, 5, money("1000")))));
        assertThat(b.id()).isNotEqualTo(a.id());
    }

    // ------------------------------------------------ tenant / member checks

    @Test
    void allocationsCannotPointAtAnotherMembersOrAnotherSchemesRecords() {
        as(member1);
        // Another member's loan in the same scheme.
        assertThatThrownBy(() -> service.create(coopA, command("1000", PaymentMethod.MTN_MOMO, ref(),
                        Allocation.loanRepayment(loanA2.getId(), money("1000")))))
                .isInstanceOf(ResourceNotFoundException.class);
        // A loan of another Saving Scheme.
        assertThatThrownBy(() -> service.create(coopA, command("1000", PaymentMethod.MTN_MOMO, ref(),
                        Allocation.loanRepayment(loanB1.getId(), money("1000")))))
                .isInstanceOf(ResourceNotFoundException.class);
        // A loan that cannot take repayments.
        Loan closed = saveLoan(coopA, memberA1, LoanStatus.CLOSED);
        assertCode(() -> service.create(coopA, command("1000", PaymentMethod.MTN_MOMO, ref(),
                Allocation.loanRepayment(closed.getId(), money("1000")))), "LOAN_NOT_REPAYABLE");
    }

    @Test
    void thePayerMustBeAnActiveMemberOfTheScheme() {
        as(accountant);
        // memberB1 belongs to scheme B only.
        assertThatThrownBy(() -> service.create(coopA, new MemberPaymentCreateCommand(
                        memberB1, money("1000"), LocalDate.now(KIGALI), PaymentMethod.MTN_MOMO, ref(), null, null, null,
                        List.of(Allocation.contribution(2028, 6, money("1000"))))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aPaymentOfAnotherSchemeIsNotFoundAndNotReviewable() {
        as(memberOfB);
        UUID inB = service.create(coopB, command("1000", PaymentMethod.MTN_MOMO, ref(),
                Allocation.contribution(2028, 7, money("1000")))).id();

        UserPrincipal accountantOfB = principal(accountantA, coopB, Set.of("ACCOUNTANT"), Set.of("CONTRIBUTION_WRITE"));
        // Accountant of scheme A, addressing the payment through scheme A's path: not found.
        as(accountant);
        assertThatThrownBy(() -> service.get(coopA, inB)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.startReview(coopA, inB)).isInstanceOf(ResourceNotFoundException.class);
        // ...and through scheme B's path: not a member of B.
        assertThatThrownBy(() -> service.startReview(coopB, inB)).isInstanceOf(ForbiddenException.class);
        assertThat(accountantOfB.getScopedCooperativeId()).isEqualTo(coopB);
        assertThat(paymentRepository.findById(inB).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUBMITTED);
    }

    @Test
    void membersCannotReadEachOthersPaymentsButStaffAndOwnersCan() {
        UUID id = submittedContribution(2028, 8, "1000");
        as(member1);
        assertThat(service.get(coopA, id).id()).isEqualTo(id);
        as(member2);
        assertThatThrownBy(() -> service.get(coopA, id)).isInstanceOf(ResourceNotFoundException.class);
        as(accountant);
        assertThat(service.get(coopA, id).id()).isEqualTo(id);
    }

    @Test
    void reviewNeedsWriteAuthorityAndNeverBelongsToThePayer() {
        UUID id = submittedContribution(2028, 9, "1000");
        as(member2);
        assertThatThrownBy(() -> service.startReview(coopA, id)).isInstanceOf(ForbiddenException.class);
        as(member1);
        assertThatThrownBy(() -> service.startReview(coopA, id)).isInstanceOf(ForbiddenException.class);

        // A staff member who is also the payer cannot review their own payment.
        as(accountant);
        UUID own = service.create(coopA, command("1000", PaymentMethod.MTN_MOMO, ref(),
                Allocation.contribution(2028, 10, money("1000")))).id();
        assertThatThrownBy(() -> service.startReview(coopA, own)).isInstanceOf(ForbiddenException.class);

        // Loan allocations need LOAN_WRITE, not just CONTRIBUTION_WRITE.
        UserPrincipal contributionOnly = principal(accountantA2, coopA, Set.of("SECRETARY"), Set.of("CONTRIBUTION_WRITE"));
        as(member1);
        UUID loanPayment = service.create(coopA, command("1000", PaymentMethod.MTN_MOMO, ref(),
                Allocation.loanRepayment(loanA1.getId(), money("1000")))).id();
        as(contributionOnly);
        assertThatThrownBy(() -> service.startReview(coopA, loanPayment)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void theSubmitterCannotVerifyAndTheDatabaseAgrees() {
        // The accountant records the payment on behalf of a member...
        as(accountant);
        MemberPaymentResponse created = service.create(coopA, new MemberPaymentCreateCommand(
                memberA1, money("1500"), LocalDate.now(KIGALI), PaymentMethod.CASH, null, null, null, null,
                List.of(Allocation.contribution(2028, 11, money("1500")))));
        assertThat(created.submittedBy()).isEqualTo(accountantA);
        assertThat(created.memberUserId()).isEqualTo(memberA1);
        service.startReview(coopA, created.id());
        // ...so they cannot also be the one who verifies it.
        assertThatThrownBy(() -> service.verify(coopA, created.id(), cashEvidence())).isInstanceOf(ForbiddenException.class);
        as(accountant2);
        assertThat(service.verify(coopA, created.id(), cashEvidence()).status()).isEqualTo(PaymentStatus.VERIFIED);
    }

    @Test
    void staffNeedsAuthorityToRecordForAnotherMember() {
        as(member2);
        assertThatThrownBy(() -> service.create(coopA, new MemberPaymentCreateCommand(
                        memberA1, money("1000"), LocalDate.now(KIGALI), PaymentMethod.CASH, null, null, null, null,
                        List.of(Allocation.contribution(2028, 12, money("1000"))))))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void evidenceMustBeAnExistingFileOfThisSchemeUploadedByTheSubmitter() {
        as(member1);
        assertThatThrownBy(() -> service.create(coopA, new MemberPaymentCreateCommand(
                        null, money("1000"), LocalDate.now(KIGALI), PaymentMethod.MTN_MOMO, ref(),
                        "cooperatives/" + coopA + "/PAYMENT/does-not-exist.pdf", null, null,
                        List.of(Allocation.contribution(2029, 1, money("1000"))))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ----------------------------------------------------------- concurrency

    @Test
    void concurrentVerifyAndRejectLeaveOneConsistentOutcome() throws Exception {
        UUID id = submittedContribution(2029, 3, "3000");
        as(accountant);
        service.startReview(coopA, id);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> verify = pool.submit((Callable<Object>) () -> {
                as(accountant2);
                go.await();
                try {
                    return service.verify(coopA, id, evidence()).status();
                } catch (BusinessException ex) {
                    return ex.getErrorCode();
                }
            });
            Future<Object> reject = pool.submit((Callable<Object>) () -> {
                as(accountant2);
                go.await();
                try {
                    return service.reject(coopA, id, "Rejected concurrently").status();
                } catch (BusinessException ex) {
                    return ex.getErrorCode();
                }
            });
            go.countDown();
            Object v = verify.get();
            Object r = reject.get();
            PaymentStatus finalStatus = paymentRepository.findById(id).orElseThrow().getStatus();
            // Whoever won, the history is a legal path and the other call was refused (or applied afterwards
            // where legal: VERIFIED -> REJECTED).
            assertThat(finalStatus).isIn(PaymentStatus.VERIFIED, PaymentStatus.REJECTED);
            assertThat(List.of(v, r)).anyMatch(o -> o == PaymentStatus.REJECTED || o == PaymentStatus.VERIFIED);
            PaymentStatus previous = null;
            for (MemberPaymentEvent e : eventsOf(id)) {
                if (previous != null) {
                    assertThat(previous.canTransitionTo(e.getToStatus())).isTrue();
                }
                previous = e.getToStatus();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSubmissionsOfOneReferenceCreateOnlyOnePayment() throws Exception {
        String reference = ref();
        int threads = 4;
        List<Object> results = runConcurrently(threads, () -> {
            as(member1);
            try {
                return service.create(coopA, command(
                                "1000", PaymentMethod.MTN_MOMO, reference, Allocation.contribution(2029, 4, money("1000"))))
                        .status();
            } catch (ConflictException ex) {
                return "CONFLICT";
            }
        });
        assertThat(results.stream().filter(r -> r == PaymentStatus.SUBMITTED)).hasSize(1);
        assertThat(results.stream().filter(r -> "CONFLICT".equals(r))).hasSize(threads - 1);
        assertThat(paymentRepository.findAll().stream().filter(p -> reference.equals(p.getTransactionReference())))
                .hasSize(1);
    }

    // ------------------------------------------- database-level safety nets

    @Test
    void migrationCreatedTheTablesAndConstraints() {
        for (String table : List.of("member_payments", "member_payment_allocations", "member_payment_events")) {
            Integer count = jdbc.queryForObject(
                    "select count(*) from information_schema.tables where lower(table_name) = ?", Integer.class, table);
            assertThat(count).as(table).isEqualTo(1);
        }
        Integer constraints = jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints where lower(constraint_name) in"
                        + " ('uq_member_payments_reference_key','chk_member_payments_verifier_independent',"
                        + "'chk_member_payment_allocations_breakdown','fk_member_payment_allocations_loan',"
                        + "'uq_loans_id_cooperative_member')",
                Integer.class);
        assertThat(constraints).isEqualTo(5);
    }

    @Test
    void theDatabaseRefusesTwoLivePaymentsWithTheSameReferenceKey() {
        String key = "MTN_MOMO|DB" + UUID.randomUUID().toString().substring(0, 8);
        insertPayment(UUID.randomUUID(), "SUBMITTED", key, "ref1");
        assertThatThrownBy(() -> insertPayment(UUID.randomUUID(), "SUBMITTED", key, "ref1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Payments without a reference (cash) have a NULL key and never collide.
        insertCashPayment(UUID.randomUUID());
        insertCashPayment(UUID.randomUUID());
        // A live payment that has a reference must hold its key.
        assertThatThrownBy(() -> insertPayment(UUID.randomUUID(), "SUBMITTED", null, "ref2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseRefusesAVerifiedPaymentThatIsNotFullyAllocatedOrSelfVerified() {
        UUID id = UUID.randomUUID();
        insertPayment(id, "SUBMITTED", "MTN_MOMO|CHK" + UUID.randomUUID().toString().substring(0, 8), "chk");
        // Verified while nothing is allocated (allocated_amount 0 <> amount).
        assertThatThrownBy(() -> jdbc.update(
                        "update member_payments set allocated_amount=0, status='VERIFIED', review_started_by=?, review_started_at=now(),"
                                + " verified_by=?, verified_at=now() where id=?",
                        accountantA, accountantA2, id))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Verified by the payer.
        assertThatThrownBy(() -> jdbc.update(
                        "update member_payments set allocated_amount=amount, status='VERIFIED', review_started_by=?,"
                                + " review_started_at=now(), verified_by=?, verified_at=now() where id=?",
                        accountantA, memberA1, id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseRefusesInconsistentAllocationsAndCrossMemberLoans() {
        UUID paymentId = UUID.randomUUID();
        insertPayment(paymentId, "SUBMITTED", "MTN_MOMO|ALC" + UUID.randomUUID().toString().substring(0, 8), "alc");
        // Breakdown that does not add up to the allocation amount.
        assertThatThrownBy(() -> insertAllocation(paymentId, memberA1, loanA1.getId(), "1000", "500", "400", "50"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // A loan that belongs to another member, even though scheme and payment match.
        assertThatThrownBy(() -> insertAllocation(paymentId, memberA1, loanA2.getId(), "1000", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        // A loan of another scheme.
        assertThatThrownBy(() -> insertAllocation(paymentId, memberA1, loanB1.getId(), "1000", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Allocation claiming a different member than its payment.
        assertThatThrownBy(() -> insertAllocation(paymentId, memberA2, loanA2.getId(), "1000", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Valid ones are accepted.
        insertAllocation(paymentId, memberA1, loanA1.getId(), "1000", "500", "400", "100");
    }

    // ---------------------------------------------------------------- helpers

    private void insertPayment(UUID id, String status, String referenceKey, String normalized) {
        jdbc.update(
                "insert into member_payments (id, cooperative_id, member_user_id, amount, allocated_amount, currency,"
                        + " payment_date, payment_method, transaction_reference, normalized_reference, reference_key,"
                        + " status, submitted_by, submitted_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?, now())",
                id, coopA, memberA1, new BigDecimal("1000"), new BigDecimal("1000"), "RWF", LocalDate.now(KIGALI),
                "MTN_MOMO", normalized, normalized, referenceKey, status, memberA1);
    }

    private void insertCashPayment(UUID id) {
        jdbc.update(
                "insert into member_payments (id, cooperative_id, member_user_id, amount, allocated_amount, currency,"
                        + " payment_date, payment_method, status, submitted_by, submitted_at)"
                        + " values (?,?,?,?,?,?,?,?,?,?, now())",
                id, coopA, memberA1, new BigDecimal("1000"), new BigDecimal("1000"), "RWF", LocalDate.now(KIGALI),
                "CASH", "SUBMITTED", memberA1);
    }

    private void insertAllocation(
            UUID paymentId, UUID memberId, UUID loanId, String amount, String principal, String interest, String penalty) {
        jdbc.update(
                "insert into member_payment_allocations (id, payment_id, cooperative_id, member_user_id,"
                        + " allocation_type, amount, loan_id, principal_amount, interest_amount, penalty_amount)"
                        + " values (?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), paymentId, coopA, memberId, "LOAN_REPAYMENT", new BigDecimal(amount), loanId,
                principal == null ? null : new BigDecimal(principal),
                interest == null ? null : new BigDecimal(interest),
                penalty == null ? null : new BigDecimal(penalty));
    }

    private UUID submittedContribution(int year, int month, String amount) {
        as(member1);
        return service.create(coopA, command(amount, PaymentMethod.MTN_MOMO, ref(),
                Allocation.contribution(year, month, money(amount)))).id();
    }

    private static PaymentVerifyRequest evidence() {
        PaymentVerifyRequest request = new PaymentVerifyRequest();
        request.setMethod(VerificationMethod.PROVIDER_CONFIRMED);
        request.setBasis("Provider confirmation seen in the merchant portal");
        request.setReference("PROVIDER-REF-1");
        return request;
    }

    private static PaymentVerifyRequest cashEvidence() {
        PaymentVerifyRequest request = new PaymentVerifyRequest();
        request.setMethod(VerificationMethod.CASH_RECEIPT_CONFIRMED);
        request.setBasis("Cash counted and receipted by the treasurer");
        return request;
    }

    /** Puts a payment into a later state without running the posting code (that has its own tests). */
    private void forceStatus(UUID id, String status) {
        if ("POSTED".equals(status)) {
            jdbc.update("update member_payments set status='POSTED', posted_by=?, posted_at=now() where id=?", accountantA, id);
        } else if ("REVERSED".equals(status)) {
            jdbc.update(
                    "update member_payments set status='REVERSED', posted_by=coalesce(posted_by, ?), posted_at=coalesce(posted_at, now()),"
                            + " reversed_by=?, reversed_at=now(), reversal_reason='test' where id=?",
                    accountantA, accountantA, id);
        }
    }

    private UUID verifiedContribution(int year, int month, String amount) {
        return verifiedContribution(year, month, amount, ref());
    }

    private UUID verifiedContribution(int year, int month, String amount, String reference) {
        as(member1);
        UUID id = service.create(coopA, command(amount, PaymentMethod.MTN_MOMO, reference,
                Allocation.contribution(year, month, money(amount)))).id();
        as(accountant);
        service.startReview(coopA, id);
        as(accountant2);
        service.verify(coopA, id, evidence());
        return id;
    }

    private List<MemberPaymentEvent> eventsOf(UUID paymentId) {
        MemberPayment payment = paymentRepository.findById(paymentId).orElseThrow();
        return eventRepository.findByCooperativeIdAndPaymentIdOrderByCreatedAtAsc(payment.getCooperativeId(), paymentId);
    }

    private List<Object> runConcurrently(int threads, Callable<Object> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<Object> results = new java.util.ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(code);
    }

    /** Runs the action as another principal (restores nothing; each test sets its own). */
    private void assertCode(Runnable action, String code, UserPrincipal actor) {
        as(actor);
        assertCode(action, code);
    }

    private static MemberPaymentCreateCommand withKey(String key, MemberPaymentCreateCommand c) {
        return new MemberPaymentCreateCommand(
                c.memberUserId(), c.amount(), c.paymentDate(), c.paymentMethod(), c.transactionReference(),
                c.evidenceFileKey(), key, c.notes(), c.allocations());
    }

    private static MemberPaymentCreateCommand command(
            String amount, PaymentMethod method, String reference, Allocation... allocations) {
        return base(amount == null ? null : money(amount), method, reference, List.of(allocations));
    }

    private static MemberPaymentCreateCommand base(
            BigDecimal amount, PaymentMethod method, String reference, List<Allocation> allocations) {
        return new MemberPaymentCreateCommand(
                null, amount, LocalDate.now(KIGALI), method, reference, null, null, null, allocations);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static String ref() {
        return "TX" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private void as(UserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private UserPrincipal principal(UUID userId, UUID cooperativeId, Set<String> roles, Set<String> permissions) {
        return UserPrincipal.builder()
                .id(userId)
                .username("u-" + userId)
                .password("")
                .roles(roles)
                .permissions(permissions)
                .cooperativeIds(Set.of(cooperativeId))
                .scopedCooperativeId(cooperativeId)
                .accountNonLocked(true)
                .enabled(true)
                .build();
    }

    private Loan saveLoan(UUID cooperativeId, UUID memberId, LoanStatus status) {
        LocalDate today = LocalDate.now(KIGALI);
        return loanRepository.saveAndFlush(Loan.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberId)
                .requestedAmount(money("100000"))
                .approvedAmount(money("100000"))
                .principalAmount(money("100000"))
                .interestRatePercent(money("2"))
                .interestType(InterestType.FLAT)
                .termMonths(6)
                .interestAmount(money("0"))
                .outstandingPrincipal(money("100000"))
                .outstandingInterest(money("0"))
                .requestDate(today.minusMonths(1))
                .disbursementDate(today.minusMonths(1))
                .dueDate(today.plusMonths(5))
                .status(status)
                .build());
    }

    private String loginAccessToken() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"superadmin","password":"ChangeMe@123!"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }

    private UUID createCooperative(String token, String prefix) throws Exception {
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(prefix + " " + UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(create.getResponse().getContentAsString()).path("data").path("id").asText());
    }

    private UUID registerMember(String token, UUID cooperativeId, String role) throws Exception {
        String username = "pay_" + UUID.randomUUID().toString().substring(0, 10);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + cooperativeId + "/members")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Pay",
                                  "lastName":"Member",
                                  "username":"%s",
                                  "email":"%s@test.local",
                                  "roleInCooperative":"%s"
                                }
                                """.formatted(username, username, role)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(register.getResponse().getContentAsString()).path("data").path("userId").asText());
    }
}
