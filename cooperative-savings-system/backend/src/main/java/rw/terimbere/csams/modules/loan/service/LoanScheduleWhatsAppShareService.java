package rw.terimbere.csams.modules.loan.service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.loan.dto.LoanScheduleWhatsAppShareRequest;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.report.dto.ReportWhatsAppShareResponse;
import rw.terimbere.csams.modules.report.dto.ReportWhatsAppStatusResponse;
import rw.terimbere.csams.modules.report.whatsapp.WhatsAppCloudClient;
import rw.terimbere.csams.modules.report.whatsapp.WhatsAppPhone;
import rw.terimbere.csams.modules.report.whatsapp.WhatsAppProperties;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

/**
 * Sends the same repayment-schedule PDF as Download PDF through the existing WhatsApp Cloud API.
 */
@Service
@RequiredArgsConstructor
public class LoanScheduleWhatsAppShareService {

    private static final String CAPTION = "OuWealth Community\nRepayment Schedule";

    private final LoanService loanService;
    private final LoanRepository loanRepository;
    private final WhatsAppCloudClient whatsAppCloudClient;
    private final WhatsAppProperties whatsAppProperties;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;

    public ReportWhatsAppStatusResponse status(UUID cooperativeId, UUID loanId) {
        authorizationService.requireMembership(cooperativeId);
        loanService.requireLoanReadAccess(requireLoan(cooperativeId, loanId));
        return ReportWhatsAppStatusResponse.builder()
                .configured(whatsAppProperties.isConfigured())
                .build();
    }

    public ReportWhatsAppShareResponse share(
            UUID cooperativeId, UUID loanId, LoanScheduleWhatsAppShareRequest request, HttpServletRequest httpRequest) {
        authorizationService.requireMembership(cooperativeId);
        loanService.requireLoanReadAccess(requireLoan(cooperativeId, loanId));
        if (request == null) {
            throw new ValidationException("Request is required");
        }
        String recipient = WhatsAppPhone.toRecipient(request.getRecipientPhone());
        if (recipient == null) {
            throw new ValidationException("Enter a valid Rwandan mobile number");
        }
        if (!whatsAppProperties.isConfigured()) {
            throw new BusinessException("WHATSAPP_NOT_CONFIGURED", "WhatsApp sharing is not configured");
        }

        LoanService.ScheduleExport export = loanService.exportSchedule(cooperativeId, loanId, "pdf", httpRequest);
        whatsAppCloudClient.sendDocument(recipient, export.content(), export.filename(), CAPTION);

        UserPrincipal principal = authorizationService.currentPrincipal();
        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.WHATSAPP_SHARE,
                "LoanSchedule",
                loanId,
                null,
                "{\"filename\":\"" + export.filename().replace("\"", "'") + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));

        return ReportWhatsAppShareResponse.builder()
                .sent(true)
                .recipient(recipient)
                .filename(export.filename())
                .build();
    }

    private Loan requireLoan(UUID cooperativeId, UUID loanId) {
        return loanRepository
                .findByIdAndCooperativeId(loanId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan", loanId));
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest request) {
        return request == null ? null : request.getHeader("User-Agent");
    }
}
