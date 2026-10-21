package rw.terimbere.csams.modules.loan.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.entity.ApprovalAction;
import rw.terimbere.csams.modules.audit.service.ApprovalTrailService;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.loan.dto.LoanGuarantorRespondRequest;
import rw.terimbere.csams.modules.loan.dto.LoanGuarantorResponse;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanGuaranteeMode;
import rw.terimbere.csams.modules.loan.entity.LoanGuarantor;
import rw.terimbere.csams.modules.loan.entity.LoanGuarantorStatus;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanGuarantorRepository;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.service.NotificationFacade;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@Service
@RequiredArgsConstructor
public class LoanGuarantorService {

    private final LoanGuarantorRepository loanGuarantorRepository;
    private final LoanRepository loanRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final ApprovalTrailService approvalTrailService;
    private final AuditService auditService;
    private final NotificationFacade notificationFacade;

    @Transactional
    public LoanGuarantorResponse assignGuarantor(
            Loan loan,
            UUID guarantorUserId,
            BigDecimal guaranteedAmount,
            UserPrincipal principal,
            HttpServletRequest httpRequest) {
        if (guarantorUserId == null) {
            throw new ValidationException("Guarantor is required");
        }
        if (guarantorUserId.equals(loan.getMemberUserId())) {
            throw new ValidationException("A member cannot guarantee their own loan");
        }
        CooperativeMembership membership = membershipRepository
                .findByCooperativeIdAndUserId(loan.getCooperativeId(), guarantorUserId)
                .orElseThrow(() -> new ValidationException("Guarantor is not a member of this cooperative"));
        if (!"ACTIVE".equalsIgnoreCase(membership.getMembershipStatus())) {
            throw new BusinessException("Only ACTIVE members can act as guarantor");
        }

        BigDecimal amount = MoneyUtils.scaleForStorage(guaranteedAmount);
        MoneyUtils.assertPositive(amount);
        BigDecimal loanAmount = loan.getRequestedAmount();
        if (amount.compareTo(loanAmount) > 0) {
            throw new ValidationException("Guaranteed amount cannot exceed the requested loan amount");
        }

        LoanGuarantor existing = loanGuarantorRepository.findByLoanId(loan.getId()).orElse(null);
        if (existing != null && existing.getStatus() == LoanGuarantorStatus.ACCEPTED) {
            throw new BusinessException("This loan already has an accepted guarantor");
        }

        Instant now = Instant.now();
        LoanGuarantor guarantor = existing == null ? new LoanGuarantor() : existing;
        guarantor.setCooperativeId(loan.getCooperativeId());
        guarantor.setLoanId(loan.getId());
        guarantor.setGuarantorUserId(guarantorUserId);
        guarantor.setGuaranteedAmount(amount);
        guarantor.setStatus(LoanGuarantorStatus.PENDING);
        guarantor.setRequestedBy(principal.getId());
        guarantor.setRequestedAt(now);
        guarantor.setRespondedAt(null);
        guarantor.setResponseComment(null);
        guarantor = loanGuarantorRepository.save(guarantor);

        String borrowerName = formatName(loan.getMemberUserId());
        approvalTrailService.append(
                loan.getCooperativeId(),
                ApprovalTrailService.ENTITY_LOAN_GUARANTOR,
                guarantor.getId(),
                principal,
                ApprovalAction.SUBMITTED,
                null,
                LoanGuarantorStatus.PENDING.name(),
                "Guarantor requested: " + borrowerName + " → " + formatName(guarantorUserId)
                        + "; Guarantee amount: " + amount);
        auditService.record(
                principal.getId(),
                loan.getCooperativeId(),
                AuditableAction.GUARANTOR_REQUEST,
                "LoanGuarantor",
                guarantor.getId(),
                null,
                "{\"loanId\":\"" + loan.getId() + "\",\"guarantorUserId\":\"" + guarantorUserId
                        + "\",\"guaranteedAmount\":\"" + amount + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        notificationFacade.notifyUser(
                guarantorUserId,
                loan.getCooperativeId(),
                NotificationType.LOAN,
                "Guarantor request",
                borrowerName + " has requested you to guarantee a loan of " + MoneyUtils.scale(amount)
                        + ". Do you accept to be their guarantor?",
                "Loan",
                loan.getId());
        return toResponse(guarantor, loan);
    }

    @Transactional
    public LoanGuarantorResponse respond(
            UUID cooperativeId,
            UUID loanId,
            LoanGuarantorRespondRequest request,
            HttpServletRequest httpRequest) {
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        Loan loan = loanRepository
                .findByIdAndCooperativeId(loanId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan", loanId));
        LoanGuarantor guarantor = loanGuarantorRepository
                .findByLoanId(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanGuarantor", loanId));

        if (!guarantor.getGuarantorUserId().equals(principal.getId())) {
            throw new ForbiddenException("A member cannot accept a guarantor request on behalf of another member");
        }
        if (guarantor.getStatus() != LoanGuarantorStatus.PENDING) {
            throw new BusinessException("This guarantor request has already been answered");
        }
        if (request == null || request.getAccepted() == null) {
            throw new ValidationException("accepted is required");
        }

        boolean accepted = Boolean.TRUE.equals(request.getAccepted());
        String previous = guarantor.getStatus().name();
        guarantor.setStatus(accepted ? LoanGuarantorStatus.ACCEPTED : LoanGuarantorStatus.REJECTED);
        guarantor.setRespondedAt(Instant.now());
        guarantor.setResponseComment(trimToNull(request.getComment()));
        guarantor = loanGuarantorRepository.save(guarantor);

        ApprovalAction action = accepted ? ApprovalAction.APPROVED : ApprovalAction.REJECTED;
        AuditableAction auditAction = accepted ? AuditableAction.GUARANTOR_ACCEPT : AuditableAction.GUARANTOR_REJECT;
        approvalTrailService.append(
                cooperativeId,
                ApprovalTrailService.ENTITY_LOAN_GUARANTOR,
                guarantor.getId(),
                principal,
                action,
                previous,
                guarantor.getStatus().name(),
                "Response: " + guarantor.getStatus().name() + "; Accepted by: " + formatName(principal.getId())
                        + (guarantor.getResponseComment() == null ? "" : "; " + guarantor.getResponseComment()));
        auditService.record(
                principal.getId(),
                cooperativeId,
                auditAction,
                "LoanGuarantor",
                guarantor.getId(),
                "{\"status\":\"" + previous + "\"}",
                "{\"status\":\"" + guarantor.getStatus() + "\",\"amount\":\"" + guarantor.getGuaranteedAmount() + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));

        String guarantorName = formatName(principal.getId());
        String body = accepted
                ? guarantorName + " accepted to guarantee your loan of "
                        + MoneyUtils.scale(guarantor.getGuaranteedAmount()) + "."
                : guarantorName + " rejected the request to guarantee your loan. The loan cannot proceed until a guarantor accepts.";
        notificationFacade.notifyUser(
                loan.getMemberUserId(),
                cooperativeId,
                NotificationType.LOAN,
                accepted ? "Guarantor accepted" : "Guarantor rejected",
                body,
                "Loan",
                loan.getId());
        return toResponse(guarantor, loan);
    }

    @Transactional(readOnly = true)
    public List<LoanGuarantorResponse> myRequests(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        return loanGuarantorRepository
                .findByCooperativeIdAndGuarantorUserIdOrderByRequestedAtDesc(cooperativeId, principal.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public LoanGuarantorResponse findForLoan(UUID loanId) {
        return loanGuarantorRepository.findByLoanId(loanId).map(this::toResponse).orElse(null);
    }

    public void requireAcceptedIfPresent(UUID loanId) {
        loanGuarantorRepository.findByLoanId(loanId).ifPresent(guarantor -> {
            if (guarantor.getStatus() != LoanGuarantorStatus.ACCEPTED) {
                throw new BusinessException(
                        "This loan cannot be approved until the guarantor has accepted the guarantee");
            }
        });
    }

    public void requireAcceptedIfRequired(Loan loan) {
        if (loan.getGuaranteeMode() == LoanGuaranteeMode.GUARANTOR) {
            LoanGuarantor guarantor = loanGuarantorRepository
                    .findByLoanId(loan.getId())
                    .orElseThrow(() -> new BusinessException(
                            "This loan cannot be approved until the guarantor has accepted the guarantee"));
            if (guarantor.getStatus() != LoanGuarantorStatus.ACCEPTED) {
                throw new BusinessException(
                        "This loan cannot be approved until the guarantor has accepted the guarantee");
            }
            return;
        }
        requireAcceptedIfPresent(loan.getId());
    }

    private LoanGuarantorResponse toResponse(LoanGuarantor guarantor) {
        Loan loan = loanRepository.findById(guarantor.getLoanId()).orElse(null);
        return toResponse(guarantor, loan);
    }

    private LoanGuarantorResponse toResponse(LoanGuarantor guarantor, Loan loan) {
        return LoanGuarantorResponse.builder()
                .id(guarantor.getId())
                .cooperativeId(guarantor.getCooperativeId())
                .loanId(guarantor.getLoanId())
                .borrowerUserId(loan == null ? null : loan.getMemberUserId())
                .borrowerName(loan == null ? null : formatName(loan.getMemberUserId()))
                .loanAmount(loan == null ? null : MoneyUtils.scale(loan.getRequestedAmount()))
                .loanStatus(loan == null ? null : loan.getStatus())
                .guarantorUserId(guarantor.getGuarantorUserId())
                .guarantorName(formatName(guarantor.getGuarantorUserId()))
                .guaranteedAmount(MoneyUtils.scale(guarantor.getGuaranteedAmount()))
                .status(guarantor.getStatus())
                .requestedBy(guarantor.getRequestedBy())
                .requestedAt(guarantor.getRequestedAt())
                .respondedAt(guarantor.getRespondedAt())
                .responseComment(guarantor.getResponseComment())
                .build();
    }

    private String formatName(UUID userId) {
        return userRepository
                .findByIdAndDeletedFalse(userId)
                .map(User::getFullName)
                .filter(StringUtils::hasText)
                .orElse("Member");
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest request) {
        return request == null ? null : request.getHeader("User-Agent");
    }
}
