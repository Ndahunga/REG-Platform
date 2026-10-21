package rw.terimbere.csams.modules.report.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.report.service.ModelFinancialSummaryService;
import rw.terimbere.csams.modules.report.service.ModelFinancialSummaryService.Summary;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/reports/model-summary")
@Tag(name = "Model financial summary")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class ModelFinancialSummaryController {

    private final ModelFinancialSummaryService service;

    @GetMapping
    @PreAuthorize("hasAuthority('REPORT_READ')")
    @Operation(
            summary = "Expected, collected, retained, rotating, entitlement, payout and arrears figures of the scheme (officers)",
            description = "Expectations are never counted as collected cash. Rotating collections are shown separately from retained capital.")
    public ResponseEntity<ApiResponse<Summary>> summary(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(service.summary(cooperativeId)));
    }
}
