package rw.terimbere.csams.modules.share.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SharePurchaseReviewRequest {

    @Size(max = 2000)
    private String rejectionReason;
}
