package rw.terimbere.csams.modules.position;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

/** Member statements: capital, income share, rotation position, loans, fines and net position. */
@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/positions")
@Tag(name = "Member positions")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class MemberPositionController {

    private final MemberPositionService service;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Every member's position and the scheme reconciliation (officers)")
    public ResponseEntity<ApiResponse<MemberPositionService.SchemePosition>> scheme(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(service.scheme(cooperativeId)));
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The signed-in member's own statement")
    public ResponseEntity<ApiResponse<MemberPositionService.MemberPosition>> mine(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(service.mine(cooperativeId)));
    }

    @GetMapping("/members/{memberUserId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "One member's statement (officers, or the member)")
    public ResponseEntity<ApiResponse<MemberPositionService.MemberPosition>> member(
            @PathVariable UUID cooperativeId, @PathVariable UUID memberUserId) {
        return ResponseEntity.ok(ApiResponse.ok(service.member(cooperativeId, memberUserId)));
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Members' positions as CSV (opens in Excel)")
    public ResponseEntity<byte[]> export(@PathVariable UUID cooperativeId) {
        var scheme = service.scheme(cooperativeId);
        StringBuilder sb = new StringBuilder();
        sb.append("Member,Shares,Opening capital,Contributed capital,Share purchases,Share-out paid,Capital,Income share,")
                .append("Rotated paid (opening),Rotated paid,Rotation received (opening),Rotation received,Rotation position,")
                .append("Contribution arrears,Loans outstanding,Fines outstanding,Unclassified paid,Net position\n");
        for (var m : scheme.members()) {
            sb.append(csv(m.memberName())).append(',').append(m.shares());
            for (BigDecimal v : new BigDecimal[] {
                m.openingCapital(), m.contributedCapital(), m.sharePurchases(), m.shareOutPaid(), m.capital(), m.incomeShare(),
                m.openingRotationPaid(), m.rotatedPaid(), m.openingRotationReceived(), m.rotationReceived(), m.rotationPosition(),
                m.contributionArrears(), m.loansOutstanding(), m.finesOutstanding(), m.unclassifiedPaid(), m.netPosition()
            }) {
                sb.append(',').append(v.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString());
            }
            sb.append('\n');
        }
        sb.append('\n')
                .append("Income pool,").append(scheme.incomePool().toPlainString()).append('\n')
                .append("Income per share,").append(scheme.incomePerShare().toPlainString()).append('\n')
                .append("Members' capital + income + rotation (expected),").append(scheme.expectedAssets().toPlainString()).append('\n')
                .append("Cash + loans out + investments (actual),").append(scheme.actualAssets().toPlainString()).append('\n')
                .append("Unexplained difference,").append(scheme.unexplainedDifference().toPlainString()).append('\n');
        byte[] body = ("﻿" + sb).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"member-positions.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    private static String csv(String v) {
        String s = v == null ? "" : v;
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
