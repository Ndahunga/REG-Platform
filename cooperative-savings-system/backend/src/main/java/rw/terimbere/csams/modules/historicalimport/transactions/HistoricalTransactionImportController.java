package rw.terimbere.csams.modules.historicalimport.transactions;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import rw.terimbere.csams.modules.historicalimport.transactions.HistoricalTransactionImportService.ApproveCommand;
import rw.terimbere.csams.modules.historicalimport.transactions.HistoricalTransactionImportService.BatchView;
import rw.terimbere.csams.modules.historicalimport.transactions.HistoricalTransactionImportService.EventView;
import rw.terimbere.csams.modules.historicalimport.transactions.HistoricalTransactionImportService.RowView;
import rw.terimbere.csams.modules.report.export.ExcelReportExporter;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

/** Multi-year historical transaction import (two-sheet workbook). Preview posts nothing; approval is independent. */
@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/historical-transactions")
@RequiredArgsConstructor
@Tag(name = "Historical transaction import")
@SecurityRequirement(name = "bearerAuth")
public class HistoricalTransactionImportController {

    private static final String UPLOAD = "hasAnyAuthority('CONTRIBUTION_WRITE','FUND_AUTHORIZE')";

    private final HistoricalTransactionImportService service;

    @GetMapping("/template")
    @PreAuthorize(UPLOAD)
    @Operation(summary = "Download the two-sheet historical transaction template (no sample rows)")
    public ResponseEntity<byte[]> template(@PathVariable UUID cooperativeId) {
        byte[] bytes = service.template(cooperativeId);
        ContentDisposition disposition = ContentDisposition.attachment().filename("historical-transactions-template.xlsx").build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(ExcelReportExporter.CONTENT_TYPE))
                .body(bytes);
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(UPLOAD)
    @Operation(summary = "Upload and validate a workbook (dry run: posts nothing; the same file returns the same batch)")
    public ResponseEntity<ApiResponse<BatchView>> preview(
            @PathVariable UUID cooperativeId, @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.ok(service.preview(cooperativeId, file)));
    }

    @GetMapping
    @PreAuthorize(UPLOAD)
    @Operation(summary = "Import history of the scheme")
    public ResponseEntity<ApiResponse<List<BatchView>>> list(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(cooperativeId)));
    }

    @GetMapping("/{importId}")
    @PreAuthorize(UPLOAD)
    public ResponseEntity<ApiResponse<BatchView>> get(@PathVariable UUID cooperativeId, @PathVariable UUID importId) {
        return ResponseEntity.ok(ApiResponse.ok(service.get(cooperativeId, importId)));
    }

    @GetMapping("/{importId}/rows")
    @PreAuthorize(UPLOAD)
    @Operation(summary = "Rows of a batch with their per-row errors")
    public ResponseEntity<ApiResponse<List<RowView>>> rows(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID importId,
            @RequestParam(name = "onlyErrors", defaultValue = "false") boolean onlyErrors) {
        return ResponseEntity.ok(ApiResponse.ok(service.rows(cooperativeId, importId, onlyErrors)));
    }

    @GetMapping("/{importId}/events")
    @PreAuthorize(UPLOAD)
    public ResponseEntity<ApiResponse<List<EventView>>> events(@PathVariable UUID cooperativeId, @PathVariable UUID importId) {
        return ResponseEntity.ok(ApiResponse.ok(service.events(cooperativeId, importId)));
    }

    @PostMapping("/{importId}/approve")
    @PreAuthorize("hasAuthority('FUND_AUTHORIZE')")
    @Operation(summary = "Approve and post a validated batch in one transaction (not the uploader)")
    public ResponseEntity<ApiResponse<BatchView>> approve(
            @PathVariable UUID cooperativeId, @PathVariable UUID importId, @RequestBody(required = false) ApproveBody body) {
        ApproveCommand command = body == null ? null : new ApproveCommand(body.isAcknowledgeVariance(), body.getNote());
        return ResponseEntity.ok(ApiResponse.ok(service.approve(cooperativeId, importId, command)));
    }

    @PostMapping("/{importId}/cancel")
    @PreAuthorize(UPLOAD)
    @Operation(summary = "Cancel a batch that has not been posted")
    public ResponseEntity<ApiResponse<BatchView>> cancel(
            @PathVariable UUID cooperativeId, @PathVariable UUID importId, @RequestBody(required = false) NoteBody body) {
        return ResponseEntity.ok(ApiResponse.ok(service.cancel(cooperativeId, importId, body == null ? null : body.getNote())));
    }

    @Data
    @NoArgsConstructor
    public static class ApproveBody {
        private boolean acknowledgeVariance;

        @Size(max = 1000)
        private String note;
    }

    @Data
    @NoArgsConstructor
    public static class NoteBody {
        @Size(max = 1000)
        private String note;
    }
}
