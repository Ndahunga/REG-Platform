package rw.terimbere.csams.modules.report.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.report.dto.ReportExportRequest;
import rw.terimbere.csams.modules.report.dto.ReportTypeResponse;
import rw.terimbere.csams.modules.report.dto.ReportWhatsAppShareRequest;
import rw.terimbere.csams.modules.report.dto.ReportWhatsAppShareResponse;
import rw.terimbere.csams.modules.report.dto.ReportWhatsAppStatusResponse;
import rw.terimbere.csams.modules.report.service.ReportService;
import rw.terimbere.csams.modules.report.service.ReportService.ReportBinaryExport;
import rw.terimbere.csams.modules.report.service.ReportWhatsAppShareService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/reports")
@RequiredArgsConstructor
@Tag(name = "Reports", description = "Report centre PDF exports")
@SecurityRequirement(name = "bearerAuth")
public class ReportController {

    private final ReportService reportService;
    private final ReportWhatsAppShareService reportWhatsAppShareService;

    @GetMapping("/types")
    @PreAuthorize("hasAuthority('REPORT_READ')")
    @Operation(summary = "List available report types")
    public ResponseEntity<ApiResponse<List<ReportTypeResponse>>> types(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(reportService.listTypes(cooperativeId)));
    }

    /**
     * Writes the PDF to the servlet response so Spring content negotiation cannot turn a
     * successful export into 406/500 when the browser sends {@code Accept: application/json}.
     */
    @PostMapping("/export")
    @PreAuthorize("hasAuthority('REPORT_READ')")
    @Operation(summary = "Export a report as PDF")
    public void export(
            @PathVariable UUID cooperativeId,
            @Valid @RequestBody ReportExportRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse)
            throws IOException {
        ReportBinaryExport export = reportService.export(cooperativeId, request, httpRequest);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(export.filename(), StandardCharsets.UTF_8)
                .build();
        httpResponse.setStatus(HttpServletResponse.SC_OK);
        httpResponse.setContentType(export.contentType());
        httpResponse.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        httpResponse.setContentLength(export.content().length);
        httpResponse.getOutputStream().write(export.content());
        httpResponse.flushBuffer();
    }

    @GetMapping("/whatsapp-status")
    @PreAuthorize("hasAuthority('REPORT_READ')")
    @Operation(summary = "Whether official WhatsApp report sharing is configured (no secrets)")
    public ResponseEntity<ApiResponse<ReportWhatsAppStatusResponse>> whatsappStatus(
            @PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(reportWhatsAppShareService.status(cooperativeId)));
    }

    @PostMapping("/share-whatsapp")
    @PreAuthorize("hasAuthority('REPORT_READ')")
    @Operation(summary = "Generate the existing PDF report and send it via WhatsApp Cloud API")
    public ResponseEntity<ApiResponse<ReportWhatsAppShareResponse>> shareWhatsApp(
            @PathVariable UUID cooperativeId,
            @Valid @RequestBody ReportWhatsAppShareRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(reportWhatsAppShareService.share(cooperativeId, request, httpRequest)));
    }
}
