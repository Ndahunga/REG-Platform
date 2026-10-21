package rw.terimbere.csams.modules.cooperative.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One-time classification of a legacy scheme. Both fields are mandatory; confirmation must be explicit. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperatingModelClassificationRequest {

    @NotBlank(message = "Operating model is required")
    @Pattern(
            regexp = "ACCUMULATING|ROTATING|HYBRID",
            message = "Operating model must be ACCUMULATING, ROTATING or HYBRID")
    private String operatingModel;

    @NotNull(message = "Confirmation is required")
    @AssertTrue(message = "Confirmation is required: classifying the operating model cannot be undone here")
    private Boolean confirmed;
}
