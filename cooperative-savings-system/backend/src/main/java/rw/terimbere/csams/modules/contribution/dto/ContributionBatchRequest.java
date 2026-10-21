package rw.terimbere.csams.modules.contribution.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContributionBatchRequest {

    @NotEmpty
    @Valid
    private List<ContributionLineRequest> lines;
}
