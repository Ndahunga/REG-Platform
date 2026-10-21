package rw.terimbere.csams.modules.specialcontribution.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpecialContributionReviewRequest {

    @Size(max = 2000)
    private String reviewNotes;
}
