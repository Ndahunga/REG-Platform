package rw.terimbere.csams.modules.cooperative.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CooperativeStatusUpdateRequest {

    @NotNull
    private CooperativeStatus status;
}
