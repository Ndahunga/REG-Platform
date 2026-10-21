package rw.terimbere.csams.modules.member.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignAdministratorRequest {

    /** When set, assign an existing user as cooperative admin. */
    private UUID userId;

    /** Used when creating a new admin user (required if userId is null). */
    @Size(max = 64)
    private String username;

    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 128)
    private String firstName;

    @Size(max = 128)
    private String lastName;

    @Size(max = 32)
    private String phone;

    @Size(min = 8, max = 128)
    private String temporaryPassword;

    public boolean isSpecified() {
        return userId != null
                || hasText(username)
                || hasText(email)
                || hasText(firstName)
                || hasText(lastName)
                || hasText(phone)
                || hasText(temporaryPassword);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
