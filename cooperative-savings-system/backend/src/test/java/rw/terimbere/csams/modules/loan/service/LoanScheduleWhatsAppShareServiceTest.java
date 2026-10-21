package rw.terimbere.csams.modules.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.loan.dto.LoanScheduleWhatsAppShareRequest;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.report.dto.ReportWhatsAppShareResponse;
import rw.terimbere.csams.modules.report.whatsapp.WhatsAppCloudClient;
import rw.terimbere.csams.modules.report.whatsapp.WhatsAppProperties;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;

@ExtendWith(MockitoExtension.class)
class LoanScheduleWhatsAppShareServiceTest {

    @Mock
    private LoanService loanService;

    @Mock
    private LoanRepository loanRepository;

    @Mock
    private WhatsAppCloudClient whatsAppCloudClient;

    @Mock
    private CooperativeAuthorizationService authorizationService;

    @Mock
    private AuditService auditService;

    private WhatsAppProperties properties;
    private LoanScheduleWhatsAppShareService service;
    private final UUID cooperativeId = UUID.randomUUID();
    private final UUID loanId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new WhatsAppProperties();
        service = new LoanScheduleWhatsAppShareService(
                loanService, loanRepository, whatsAppCloudClient, properties, authorizationService, auditService);
    }

    @Test
    void status_doesNotExposeCredentials() {
        properties.setEnabled(true);
        properties.setAccessToken("super-secret-token");
        properties.setPhoneNumberId("phone-id-secret");
        when(loanRepository.findByIdAndCooperativeId(loanId, cooperativeId)).thenReturn(Optional.of(new Loan()));

        var status = service.status(cooperativeId, loanId);

        assertThat(status.isConfigured()).isTrue();
        assertThat(status.toString()).doesNotContain("super-secret-token");
        assertThat(status.toString()).doesNotContain("phone-id-secret");
        verify(authorizationService).requireMembership(cooperativeId);
    }

    @Test
    void status_wrongCooperative_isNotFound() {
        when(loanRepository.findByIdAndCooperativeId(loanId, cooperativeId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.status(cooperativeId, loanId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void share_whenDisabled_doesNotGenerateOrSend() {
        when(loanRepository.findByIdAndCooperativeId(loanId, cooperativeId)).thenReturn(Optional.of(new Loan()));
        LoanScheduleWhatsAppShareRequest request = new LoanScheduleWhatsAppShareRequest();
        request.setRecipientPhone("0788123456");

        assertThatThrownBy(() -> service.share(cooperativeId, loanId, request, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not configured");
        verify(loanService, never()).exportSchedule(any(), any(), any(), any());
        verify(whatsAppCloudClient, never()).sendDocument(any(), any(), any(), any());
    }

    @Test
    void share_rejectsInvalidPhone() {
        enableWhatsApp();
        when(loanRepository.findByIdAndCooperativeId(loanId, cooperativeId)).thenReturn(Optional.of(new Loan()));
        LoanScheduleWhatsAppShareRequest request = new LoanScheduleWhatsAppShareRequest();
        request.setRecipientPhone("not-a-phone");

        assertThatThrownBy(() -> service.share(cooperativeId, loanId, request, null))
                .isInstanceOf(ValidationException.class);
        verify(loanService, never()).exportSchedule(any(), any(), any(), any());
        verify(whatsAppCloudClient, never()).sendDocument(any(), any(), any(), any());
    }

    @Test
    void share_whenConfigured_reusesPdfExportAndSendsViaClient() {
        enableWhatsApp();
        when(loanRepository.findByIdAndCooperativeId(loanId, cooperativeId)).thenReturn(Optional.of(new Loan()));
        when(authorizationService.currentPrincipal())
                .thenReturn(UserPrincipal.builder()
                        .id(UUID.randomUUID())
                        .username("officer")
                        .password("")
                        .roles(java.util.Set.of("ACCOUNTANT"))
                        .permissions(java.util.Set.of("LOAN_READ"))
                        .cooperativeIds(java.util.Set.of(cooperativeId))
                        .accountNonLocked(true)
                        .enabled(true)
                        .build());
        when(loanService.exportSchedule(eq(cooperativeId), eq(loanId), eq("pdf"), any()))
                .thenReturn(new LoanService.ScheduleExport(
                        "%PDF-test".getBytes(), "application/pdf", "repayment-schedule-jane-doe.pdf"));

        LoanScheduleWhatsAppShareRequest request = new LoanScheduleWhatsAppShareRequest();
        request.setRecipientPhone("0788123456");

        ReportWhatsAppShareResponse response = service.share(cooperativeId, loanId, request, null);

        assertThat(response.isSent()).isTrue();
        assertThat(response.getRecipient()).isEqualTo("250788123456");
        assertThat(response.getFilename()).isEqualTo("repayment-schedule-jane-doe.pdf");
        assertThat(response.getFilename()).doesNotContain(loanId.toString());

        ArgumentCaptor<byte[]> pdf = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> caption = ArgumentCaptor.forClass(String.class);
        verify(whatsAppCloudClient)
                .sendDocument(
                        eq("250788123456"),
                        pdf.capture(),
                        eq("repayment-schedule-jane-doe.pdf"),
                        caption.capture());
        assertThat(new String(pdf.getValue())).startsWith("%PDF");
        assertThat(caption.getValue()).contains("OuWealth Community");
        assertThat(caption.getValue()).contains("Repayment Schedule");
        assertThat(caption.getValue()).doesNotContain(loanId.toString());
        verify(whatsAppCloudClient, never()).sendText(any(), any());
        verify(loanService).requireLoanReadAccess(any());
    }

    @Test
    void share_whenReadDenied_doesNotGenerateOrSend() {
        when(loanRepository.findByIdAndCooperativeId(loanId, cooperativeId)).thenReturn(Optional.of(new Loan()));
        org.mockito.Mockito.doThrow(new ForbiddenException())
                .when(loanService)
                .requireLoanReadAccess(any());
        LoanScheduleWhatsAppShareRequest request = new LoanScheduleWhatsAppShareRequest();
        request.setRecipientPhone("0788123456");

        assertThatThrownBy(() -> service.share(cooperativeId, loanId, request, null))
                .isInstanceOf(ForbiddenException.class);
        verify(loanService, never()).exportSchedule(any(), any(), any(), any());
        verify(whatsAppCloudClient, never()).sendDocument(any(), any(), any(), any());
    }

    @Test
    void share_sendFailure_doesNotAuditAndDoesNotCallTextChannel() {
        enableWhatsApp();
        when(loanRepository.findByIdAndCooperativeId(loanId, cooperativeId)).thenReturn(Optional.of(new Loan()));
        when(loanService.exportSchedule(eq(cooperativeId), eq(loanId), eq("pdf"), any()))
                .thenReturn(new LoanService.ScheduleExport(
                        "%PDF-test".getBytes(), "application/pdf", "repayment-schedule.pdf"));
        org.mockito.Mockito.doThrow(new BusinessException("WHATSAPP_SEND_FAILED", "WhatsApp could not send the report"))
                .when(whatsAppCloudClient)
                .sendDocument(any(), any(), any(), any());

        LoanScheduleWhatsAppShareRequest request = new LoanScheduleWhatsAppShareRequest();
        request.setRecipientPhone("+250 788 123 456");

        assertThatThrownBy(() -> service.share(cooperativeId, loanId, request, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("could not send");
        verify(auditService, never())
                .record(
                        any(),
                        any(),
                        any(rw.terimbere.csams.shared.auditing.AuditableAction.class),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
        verify(whatsAppCloudClient, never()).sendText(any(), any());
    }

    private void enableWhatsApp() {
        properties.setEnabled(true);
        properties.setAccessToken("super-secret-token");
        properties.setPhoneNumberId("1234567890");
    }
}
