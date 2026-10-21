package rw.terimbere.csams.modules.fine.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinePaymentReviewRequest {

    @Size(max = 2000)
    private String reviewNotes;
}
