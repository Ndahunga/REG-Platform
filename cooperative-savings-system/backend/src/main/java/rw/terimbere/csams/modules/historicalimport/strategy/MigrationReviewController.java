package rw.terimbere.csams.modules.historicalimport.strategy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

/** For platform administrators: which Saving Schemes still need their migration reviewed. */
@RestController
@RequestMapping("/api/v1/platform/migration-reviews")
@RequiredArgsConstructor
@Tag(name = "Historical Import", description = "Schemes whose migration needs review")
@SecurityRequirement(name = "bearerAuth")
public class MigrationReviewController {

    private final HistoricalMigrationGuard guard;
    private final CooperativeRepository cooperativeRepository;

    public record ReviewItem(UUID cooperativeId, String cooperativeName, MigrationStrategy strategy, LocalDate cutoverDate, String source) {}

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Schemes flagged for migration review (they accept no further historical import until resolved)")
    public ResponseEntity<ApiResponse<List<ReviewItem>>> list() {
        List<ReviewItem> items = guard.underReview().stream()
                .map(r -> new ReviewItem(
                        r.getCooperativeId(),
                        cooperativeRepository.findById(r.getCooperativeId()).map(c -> c.getName()).orElse(""),
                        r.getStrategy(),
                        r.getCutoverDate(),
                        r.getSource()))
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(items));
    }
}
