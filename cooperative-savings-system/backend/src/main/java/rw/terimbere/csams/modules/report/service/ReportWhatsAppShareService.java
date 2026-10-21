package rw.terimbere.csams.modules.report.service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.report.dto.ReportWhatsAppShareRequest;
import rw.terimbere.csams.modules.report.dto.ReportWhatsAppShareResponse;
import rw.terimbere.csams.modules.report.dto.ReportWhatsAppStatusResponse;
import rw.terimbere.csams.modules.report.whatsapp.WhatsAppCloudClient;
import rw.terimbere.csams.modules.report.whatsapp.WhatsAppPhone;
import rw.terimbere.csams.modules.report.whatsapp.WhatsAppProperties;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

@Service
@RequiredArgsConstructor
public class ReportWhatsAppShareService {

    private final ReportService reportService;
    private final WhatsAppCloudClient whatsAppCloudClient;
    private final WhatsAppProperties whatsAppProperties;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public ReportWhatsAppStatusResponse status(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        return ReportWhatsAppStatusResponse.builder()
                .configured(whatsAppProperties.isConfigured())
                .build();
    }

    public ReportWhatsAppShareResponse share(
            UUID cooperativeId, ReportWhatsAppShareRequest request, HttpServletRequest httpRequest) {
        authorizationService.requireMembership(cooperativeId);
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

        ReportService.ReportBinaryExport export = reportService.export(cooperativeId, request, httpRequest);
        String caption = "OuWealth Community report: " + request.getReportType();
        whatsAppCloudClient.sendDocument(recipient, export.content(), export.filename(), caption);

        UserPrincipal principal = authorizationService.currentPrincipal();
        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.WHATSAPP_SHARE,
                "Report",
                null,
                null,
                "{\"reportType\":\""
                        + request.getReportType()
                        + "\",\"filename\":\""
                        + export.filename().replace("\"", "'")
                        + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));

        return ReportWhatsAppShareResponse.builder()
                .sent(true)
                .recipient(recipient)
                .filename(export.filename())
                .build();
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
