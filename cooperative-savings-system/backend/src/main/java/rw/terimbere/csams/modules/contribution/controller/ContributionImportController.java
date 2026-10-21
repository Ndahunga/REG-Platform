package rw.terimbere.csams.modules.contribution.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
import rw.terimbere.csams.modules.contribution.dto.ContributionImportPreviewResponse;
import rw.terimbere.csams.modules.contribution.dto.ContributionImportSummaryResponse;
import rw.terimbere.csams.modules.contribution.dto.ContributionResponse;
import rw.terimbere.csams.modules.contribution.service.ContributionImportService;
import rw.terimbere.csams.modules.report.export.ExcelReportExporter;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/contributions/import")
@RequiredArgsConstructor
@Tag(name = "Contribution Import", description = "Excel contribution import workflow")
@SecurityRequirement(name = "bearerAuth")
public class ContributionImportController {

    private final ContributionImportService contributionImportService;

    @GetMapping("/template")
    @PreAuthorize("hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(summary = "Download contribution import XLSX template")
    public ResponseEntity<byte[]> template(@PathVariable UUID cooperativeId) {
        byte[] bytes = contributionImportService.downloadTemplate(cooperativeId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("contribution-import-template.xlsx")
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(ExcelReportExporter.CONTENT_TYPE))
                .body(bytes);
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(summary = "Upload and preview contribution Excel (does not save contributions)")
    public ResponseEntity<ApiResponse<ContributionImportPreviewResponse>> preview(
            @PathVariable UUID cooperativeId,
            @RequestParam int year,
            @RequestParam int month,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                contributionImportService.preview(cooperativeId, year, month, file, httpRequest)));
    }

    @PostMapping("/{importId}/confirm")
    @PreAuthorize("hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(summary = "Confirm import — persists valid rows via contribution batchSave")
    public ResponseEntity<ApiResponse<List<ContributionResponse>>> confirm(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID importId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                contributionImportService.confirm(cooperativeId, importId, httpRequest)));
    }

    @PostMapping("/{importId}/cancel")
    @PreAuthorize("hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(summary = "Cancel a pending import")
    public ResponseEntity<ApiResponse<ContributionImportSummaryResponse>> cancel(
            @PathVariable UUID cooperativeId, @PathVariable UUID importId) {
        return ResponseEntity.ok(ApiResponse.ok(contributionImportService.cancel(cooperativeId, importId)));
    }

    @GetMapping("/{importId}")
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ') or hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(summary = "Get import status and preview rows")
    public ResponseEntity<ApiResponse<ContributionImportPreviewResponse>> get(
            @PathVariable UUID cooperativeId, @PathVariable UUID importId) {
        return ResponseEntity.ok(ApiResponse.ok(contributionImportService.getImport(cooperativeId, importId)));
    }

    @GetMapping("/history")
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ') or hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(summary = "List contribution imports for the cooperative")
    public ResponseEntity<ApiResponse<List<ContributionImportSummaryResponse>>> history(
            @PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(contributionImportService.history(cooperativeId)));
    }
}
