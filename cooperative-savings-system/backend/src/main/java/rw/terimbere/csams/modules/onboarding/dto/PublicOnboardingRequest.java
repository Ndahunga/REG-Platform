package rw.terimbere.csams.modules.onboarding.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicOnboardingRequest {

    @NotNull
    @Valid
    private PublicOnboardingCooperativeRequest cooperative;

    @NotNull
    @Valid
    private PublicOnboardingCreatorRequest creator;
}
