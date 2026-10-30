package rw.terimbere.csams.modules.incomeexpense.expense;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/expenses")
@RequiredArgsConstructor
@Tag(name = "Scheme expenses")
@SecurityRequirement(name = "bearerAuth")
public class ExpenseController {

    private final ExpenseService expenses;

    public record BankChargesRequest(List<UUID> lineIds) {}

    @GetMapping
    @Operation(summary = "Scheme expenses with their documents and what reading them found; who may record and approve")
    public ResponseEntity<ApiResponse<ExpenseService.Overview>> overview(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(expenses.overview(cooperativeId)));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Record an expense with its supporting document (required); the document's amount is checked")
    public ResponseEntity<ApiResponse<ExpenseService.ExpenseView>> record(
            @PathVariable UUID cooperativeId,
            @RequestPart(name = "file", required = false) MultipartFile file,
            @RequestParam("expenseType") String expenseType,
            @RequestParam("amount") BigDecimal amount,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "description", required = false) String description,
            @RequestParam(name = "reference", required = false) String reference,
            @RequestParam(name = "explanation", required = false) String explanation,
            HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(expenses.record(cooperativeId, file,
                new ExpenseService.RecordRequest(expenseType, amount, date, description, reference, explanation), http)));
    }

    @GetMapping("/bank-charge-lines")
    @Operation(summary = "Bank charges on uploaded statements that are not recorded yet")
    public ResponseEntity<ApiResponse<List<ExpenseService.BankChargeLine>>> bankChargeLines(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(expenses.bankChargeLines(cooperativeId)));
    }

    @PostMapping("/bank-charges")
    @Operation(summary = "Record bank charges from statement lines (the statement is the evidence)")
    public ResponseEntity<ApiResponse<List<ExpenseService.ExpenseView>>> recordBankCharges(
            @PathVariable UUID cooperativeId, @RequestBody BankChargesRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(expenses.recordBankCharges(cooperativeId, request.lineIds(), http)));
    }
}
