package rw.terimbere.csams.modules.platform.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Creates a system SUPER_ADMIN. There is intentionally no roles field: this endpoint always creates exactly
 * a system administrator, and any client-supplied roles are ignored.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuperAdminCreateRequest {

    @NotBlank
    @Size(max = 128)
    private String firstName;

    @NotBlank
    @Size(max = 128)
    private String lastName;

    @NotBlank
    @Size(min = 3, max = 64)
    private String username;

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 32)
    private String phone;

    @ToString.Exclude
    @NotBlank
    @Size(min = 8, max = 128)
    private String password;
}
