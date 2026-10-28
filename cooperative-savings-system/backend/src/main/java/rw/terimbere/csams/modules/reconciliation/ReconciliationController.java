package rw.terimbere.csams.modules.reconciliation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
import rw.terimbere.csams.shared.common.dto.ApiResponse;

/** Bank reconciliation. Officers see it; the Accountant, President and Vice President change it (checked in the service). */
@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/reconciliation")
@RequiredArgsConstructor
@Tag(name = "Bank reconciliation")
@SecurityRequirement(name = "bearerAuth")
public class ReconciliationController {

    private final ReconciliationService service;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Statements uploaded, lines by status, records not found on any statement, and the balance check")
    public ResponseEntity<ApiResponse<ReconciliationService.Overview>> overview(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(service.overview(cooperativeId)));
    }

    @PostMapping(value = "/statements", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Upload a bank or mobile-money statement (PDF, Excel or CSV); its lines are matched straight away")
    public ResponseEntity<ApiResponse<ReconciliationService.StatementSummary>> upload(
            @PathVariable UUID cooperativeId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "accountLabel", required = false) String accountLabel,
            HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(service.upload(cooperativeId, file, source, accountLabel, http)));
    }

    @GetMapping("/statements/{statementId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "A statement with every line, what it was matched with, and the app records not found on it")
    public ResponseEntity<ApiResponse<ReconciliationService.StatementDetail>> statement(
            @PathVariable UUID cooperativeId, @PathVariable UUID statementId) {
        return ResponseEntity.ok(ApiResponse.ok(service.statement(cooperativeId, statementId)));
    }

    @GetMapping("/lines")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Lines of all statements in one status (UNMATCHED, SUGGESTED, MATCHED, CONFIRMED, EXPLAINED)")
    public ResponseEntity<ApiResponse<List<ReconciliationService.LineView>>> lines(
            @PathVariable UUID cooperativeId, @RequestParam("status") String status) {
        return ResponseEntity.ok(ApiResponse.ok(service.linesByStatus(cooperativeId, status)));
    }

    @GetMapping("/lines/{lineId}/candidates")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "App records that could explain a line, closest amounts and dates first")
    public ResponseEntity<ApiResponse<List<StatementMatcher.Candidate>>> candidates(
            @PathVariable UUID cooperativeId, @PathVariable UUID lineId) {
        return ResponseEntity.ok(ApiResponse.ok(service.candidates(cooperativeId, lineId)));
    }

    @PostMapping("/lines/{lineId}/confirm")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Accept the suggestion of a line")
    public ResponseEntity<ApiResponse<ReconciliationService.LineView>> confirm(
            @PathVariable UUID cooperativeId, @PathVariable UUID lineId, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(service.confirm(cooperativeId, lineId, http)));
    }

    @PostMapping("/lines/{lineId}/link")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Link a line with the payments or ledger entries it pays for (their amounts must add up to the line)")
    public ResponseEntity<ApiResponse<ReconciliationService.LineView>> link(
            @PathVariable UUID cooperativeId, @PathVariable UUID lineId,
            @RequestBody ReconciliationService.LinkRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(service.link(cooperativeId, lineId, request, http)));
    }

    @PostMapping("/lines/{lineId}/explain")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Say what a line is when no app record stands for it (bank charge, transfer between accounts, ...)")
    public ResponseEntity<ApiResponse<ReconciliationService.LineView>> explain(
            @PathVariable UUID cooperativeId, @PathVariable UUID lineId,
            @RequestBody ReconciliationService.ExplainRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(service.explain(cooperativeId, lineId, request, http)));
    }

    @PostMapping("/lines/{lineId}/undo")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Undo the reconciliation of a line (not once a payment was verified with it)")
    public ResponseEntity<ApiResponse<ReconciliationService.LineView>> undo(
            @PathVariable UUID cooperativeId, @PathVariable UUID lineId, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(service.undo(cooperativeId, lineId, http)));
    }

    @PostMapping("/confirm-all")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Accept every suggestion (of one statement, or of all)")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> confirmAll(
            @PathVariable UUID cooperativeId, @RequestParam(name = "statementId", required = false) UUID statementId,
            HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(Map.of("confirmed", service.confirmAll(cooperativeId, statementId, http))));
    }

    @PostMapping("/rematch")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Match the open lines again (for example after more payments were submitted)")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> rematch(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(service.rematch(cooperativeId)));
    }

    @PostMapping("/verify-payments")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Verify the member payments reconciled with statement lines, the line being their evidence")
    public ResponseEntity<ApiResponse<List<ReconciliationService.VerifyResult>>> verify(
            @PathVariable UUID cooperativeId, @RequestParam(name = "statementId", required = false) UUID statementId) {
        return ResponseEntity.ok(ApiResponse.ok(service.verifyReconciled(cooperativeId, statementId)));
    }
}
