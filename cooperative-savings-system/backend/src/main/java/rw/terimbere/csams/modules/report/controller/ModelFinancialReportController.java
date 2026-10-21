package rw.terimbere.csams.modules.report.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.report.service.ModelFinancialReportService;
import rw.terimbere.csams.modules.report.service.ModelFinancialReportService.ModelReport;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/reports/model-report")
@Tag(name = "Model financial report")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class ModelFinancialReportController {

    private final ModelFinancialReportService service;

    @GetMapping
    @PreAuthorize("hasAuthority('REPORT_READ')")
    @Operation(
            summary = "The scheme's financial report in the terms of its operating model (officers)",
            description = "Built from posted records only. Earnings distribution is reported UNAVAILABLE_PENDING_POLICY.")
    public ResponseEntity<ApiResponse<ModelReport>> report(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.ok(service.report(cooperativeId, from, to)));
    }
}
