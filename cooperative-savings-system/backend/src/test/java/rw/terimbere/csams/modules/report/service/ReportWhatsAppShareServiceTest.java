package rw.terimbere.csams.modules.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.report.dto.ReportType;
import rw.terimbere.csams.modules.report.dto.ReportWhatsAppShareRequest;
import rw.terimbere.csams.modules.report.dto.ReportWhatsAppShareResponse;
import rw.terimbere.csams.modules.report.whatsapp.WhatsAppCloudClient;
import rw.terimbere.csams.modules.report.whatsapp.WhatsAppProperties;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

@ExtendWith(MockitoExtension.class)
class ReportWhatsAppShareServiceTest {

    @Mock
    private ReportService reportService;

    @Mock
    private WhatsAppCloudClient whatsAppCloudClient;

    @Mock
    private CooperativeAuthorizationService authorizationService;

    @Mock
    private AuditService auditService;

    private WhatsAppProperties properties;
    private ReportWhatsAppShareService service;
    private final UUID cooperativeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new WhatsAppProperties();
        service = new ReportWhatsAppShareService(
                reportService, whatsAppCloudClient, properties, authorizationService, auditService);
    }

    @Test
    void status_doesNotExposeCredentials() {
        properties.setEnabled(true);
        properties.setAccessToken("super-secret-token");
        properties.setPhoneNumberId("phone-id-secret");
        var status = service.status(cooperativeId);
        assertThat(status.isConfigured()).isTrue();
        assertThat(status.toString()).doesNotContain("super-secret-token");
        assertThat(status.toString()).doesNotContain("phone-id-secret");
        verify(authorizationService).requireMembership(cooperativeId);
    }

    @Test
    void share_whenDisabled_doesNotGenerateOrSend() {
        ReportWhatsAppShareRequest request = new ReportWhatsAppShareRequest();
        request.setReportType(ReportType.CONTRIBUTIONS);
        request.setRecipientPhone("0788123456");

        assertThatThrownBy(() -> service.share(cooperativeId, request, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not configured");
        verify(reportService, never()).export(any(), any(), any());
        verify(whatsAppCloudClient, never()).sendDocument(any(), any(), any(), any());
    }

    @Test
    void share_rejectsInvalidPhone() {
        enableWhatsApp();
        ReportWhatsAppShareRequest request = new ReportWhatsAppShareRequest();
        request.setReportType(ReportType.CONTRIBUTIONS);
        request.setRecipientPhone("not-a-phone");

        assertThatThrownBy(() -> service.share(cooperativeId, request, null))
                .isInstanceOf(ValidationException.class);
        verify(reportService, never()).export(any(), any(), any());
        verify(whatsAppCloudClient, never()).sendDocument(any(), any(), any(), any());
    }

    @Test
    void share_whenConfigured_reusesPdfExportAndSendsViaClient() {
        enableWhatsApp();
        when(authorizationService.currentPrincipal())
                .thenReturn(UserPrincipal.builder()
                        .id(UUID.randomUUID())
                        .username("officer")
                        .password("")
                        .roles(java.util.Set.of("ACCOUNTANT"))
                        .permissions(java.util.Set.of("REPORT_READ"))
                        .cooperativeIds(java.util.Set.of(cooperativeId))
                        .accountNonLocked(true)
                        .enabled(true)
                        .build());
        when(reportService.export(eq(cooperativeId), any(), any()))
                .thenReturn(new ReportService.ReportBinaryExport(
                        "%PDF-test".getBytes(), "application/pdf", "coop_contributions.pdf"));

        ReportWhatsAppShareRequest request = new ReportWhatsAppShareRequest();
        request.setReportType(ReportType.CONTRIBUTIONS);
        request.setRecipientPhone("0788123456");

        ReportWhatsAppShareResponse response = service.share(cooperativeId, request, null);

        assertThat(response.isSent()).isTrue();
        assertThat(response.getRecipient()).isEqualTo("250788123456");
        assertThat(response.getFilename()).isEqualTo("coop_contributions.pdf");
        assertThat(response.toString()).doesNotContain("super-secret-token");

        ArgumentCaptor<byte[]> pdf = ArgumentCaptor.forClass(byte[].class);
        verify(whatsAppCloudClient)
                .sendDocument(eq("250788123456"), pdf.capture(), eq("coop_contributions.pdf"), any());
        assertThat(new String(pdf.getValue())).startsWith("%PDF");
        verify(whatsAppCloudClient, never()).sendText(any(), any());
    }

    private void enableWhatsApp() {
        properties.setEnabled(true);
        properties.setAccessToken("super-secret-token");
        properties.setPhoneNumberId("1234567890");
    }
}
