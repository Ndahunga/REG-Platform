package rw.terimbere.csams.modules.cooperative.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeStatus;
import rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CooperativeSummaryResponse {

    private UUID id;
    private String name;
    private CooperativeStatus status;
    private String currency;
    private String logoUrl;
    private SchemeOperatingModel operatingModel;
}
