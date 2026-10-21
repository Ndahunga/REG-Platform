package rw.terimbere.csams.modules.platform.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.user.entity.AccountStatus;

/** Only {@code ACTIVE} (reactivate) and {@code INACTIVE} (deactivate) are accepted. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuperAdminStatusRequest {

    @NotNull
    private AccountStatus status;
}
