package rw.terimbere.csams.modules.share.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SharePurchaseRequest {

    @NotNull
    @Min(1)
    @Max(1000)
    private Integer numberOfShares;

    /** Officers with CONTRIBUTION_WRITE may submit for another member. */
    private UUID memberUserId;

    @NotNull
    @PastOrPresent(message = "Payment date cannot be in the future")
    private LocalDate paymentDate;

    @Size(max = 128)
    private String paymentReference;

    @NotBlank(message = "Payment proof is required")
    @Size(max = 512)
    private String evidenceFileKey;

    @Size(max = 2000)
    private String notes;
}
