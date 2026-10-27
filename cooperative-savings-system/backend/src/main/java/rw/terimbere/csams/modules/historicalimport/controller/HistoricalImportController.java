package rw.terimbere.csams.modules.historicalimport.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import rw.terimbere.csams.modules.historicalimport.dto.HistoricalImportConfirmResponse;
import rw.terimbere.csams.modules.historicalimport.dto.HistoricalImportPreviewResponse;
import rw.terimbere.csams.modules.historicalimport.dto.HistoricalImportSummaryResponse;
import rw.terimbere.csams.modules.historicalimport.service.HistoricalImportService;
import rw.terimbere.csams.modules.report.export.ExcelReportExporter;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/historical-imports")
@RequiredArgsConstructor
@Tag(name = "Historical Import", description = "Excel historical data migration")
@SecurityRequirement(name = "bearerAuth")
public class HistoricalImportController {

    private final HistoricalImportService historicalImportService;

    @GetMapping("/template")
    @PreAuthorize(
            "hasRole('PRESIDENT') or hasRole('VICE_PRESIDENT') or hasRole('SUPER_ADMIN') or hasRole('COOPERATIVE_ADMIN')")
    @Operation(summary = "Download the Ikimina opening-balance migration XLSX template (2 sheets: members and share capital)")
    public ResponseEntity<byte[]> template(@PathVariable UUID cooperativeId) {
        byte[] bytes = historicalImportService.downloadTemplate(cooperativeId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("ikimina-migration-template.xlsx")
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(ExcelReportExporter.CONTENT_TYPE))
                .body(bytes);
    }

    @GetMapping("/loan-template")
    @PreAuthorize(
            "hasRole('PRESIDENT') or hasRole('VICE_PRESIDENT') or hasRole('SUPER_ADMIN') or hasRole('COOPERATIVE_ADMIN')")
    @Operation(summary = "Download the separate opening-loans XLSX template (1 sheet: Loan Registration)")
    public ResponseEntity<byte[]> loanTemplate(@PathVariable UUID cooperativeId) {
        byte[] bytes = historicalImportService.downloadLoanTemplate(cooperativeId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("ikimina-opening-loans-template.xlsx")
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(ExcelReportExporter.CONTENT_TYPE))
                .body(bytes);
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(
            "hasRole('PRESIDENT') or hasRole('VICE_PRESIDENT') or hasRole('SUPER_ADMIN') or hasRole('COOPERATIVE_ADMIN')")
    @Operation(summary = "Upload and validate an opening-balance workbook for a cutover date (persists no financial records)")
    public ResponseEntity<ApiResponse<HistoricalImportPreviewResponse>> preview(
            @PathVariable UUID cooperativeId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "cutoverDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cutoverDate,
            @RequestParam(name = "openingAvailableFunds", required = false) java.math.BigDecimal openingAvailableFunds,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(historicalImportService.preview(cooperativeId, file, cutoverDate, openingAvailableFunds, httpRequest)));
    }

    @PostMapping("/{importId}/confirm")
    @PreAuthorize(
            "hasRole('PRESIDENT') or hasRole('VICE_PRESIDENT') or hasRole('SUPER_ADMIN') or hasRole('COOPERATIVE_ADMIN')")
    @Operation(summary = "Validate (confirm) a valid upload in one transaction; a corrected upload replaces the earlier one and needs a reason")
    public ResponseEntity<ApiResponse<HistoricalImportConfirmResponse>> confirm(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID importId,
            @org.springframework.web.bind.annotation.RequestBody(required = false) ConfirmRequest body,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(historicalImportService.confirm(
                cooperativeId, importId, body == null ? null : body.reason(), httpRequest)));
    }

    /** Why a corrected upload replaces the earlier one (required for a correction only). */
    public record ConfirmRequest(String reason) {}

    @PostMapping("/{importId}/cancel")
    @PreAuthorize(
            "hasRole('PRESIDENT') or hasRole('VICE_PRESIDENT') or hasRole('SUPER_ADMIN') or hasRole('COOPERATIVE_ADMIN')")
    @Operation(summary = "Cancel a pre-confirm historical import")
    public ResponseEntity<ApiResponse<HistoricalImportSummaryResponse>> cancel(
            @PathVariable UUID cooperativeId, @PathVariable UUID importId, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(historicalImportService.cancel(cooperativeId, importId, httpRequest)));
    }

    @GetMapping("/{importId}")
    @PreAuthorize(
            "hasRole('PRESIDENT') or hasRole('VICE_PRESIDENT') or hasRole('SUPER_ADMIN') or hasRole('COOPERATIVE_ADMIN')")
    @Operation(summary = "Get historical import preview details")
    public ResponseEntity<ApiResponse<HistoricalImportPreviewResponse>> get(
            @PathVariable UUID cooperativeId, @PathVariable UUID importId) {
        return ResponseEntity.ok(ApiResponse.ok(historicalImportService.get(cooperativeId, importId)));
    }

    @GetMapping
    @PreAuthorize(
            "hasRole('PRESIDENT') or hasRole('VICE_PRESIDENT') or hasRole('SUPER_ADMIN') or hasRole('COOPERATIVE_ADMIN')")
    @Operation(summary = "List historical imports for the cooperative")
    public ResponseEntity<ApiResponse<List<HistoricalImportSummaryResponse>>> history(
            @PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(historicalImportService.history(cooperativeId)));
    }
}
