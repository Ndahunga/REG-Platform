package rw.terimbere.csams.modules.platform.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.user.entity.AccountStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuperAdminResponse {

    private UUID id;
    private String firstName;
    private String lastName;
    private String fullName;
    private String username;
    private String email;
    private String phone;
    private AccountStatus accountStatus;
    private Instant lastLoginAt;
    private Instant createdAt;
    private boolean currentUser;
}
