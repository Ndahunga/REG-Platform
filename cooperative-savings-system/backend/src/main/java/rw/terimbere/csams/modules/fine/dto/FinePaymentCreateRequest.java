package rw.terimbere.csams.modules.fine.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.fine.entity.FinePaymentMethod;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinePaymentCreateRequest {

    @NotNull
    @DecimalMin(value = "0.01", inclusive = true)
    private BigDecimal amount;

    @NotNull
    @PastOrPresent(message = "Payment date cannot be in the future")
    private LocalDate paymentDate;

    @NotNull
    private FinePaymentMethod paymentMethod;

    @Size(max = 255)
    private String paymentMethodDetail;

    @Size(max = 128)
    private String paymentReference;

    @Size(max = 2000)
    private String notes;

    @Size(max = 512)
    private String evidenceFileKey;

    @AssertTrue(message = "paymentMethodDetail is recommended when paymentMethod is OTHER")
    public boolean isOtherDetailConsistent() {
        // Detail is optional even for OTHER; always valid at bean level.
        return true;
    }
}
