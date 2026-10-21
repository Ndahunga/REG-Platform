package rw.terimbere.csams.modules.fine.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FineGenerateRequest {

    @Min(2000)
    @Max(2100)
    private Integer year;

    @Min(1)
    @Max(12)
    private Integer month;
}
