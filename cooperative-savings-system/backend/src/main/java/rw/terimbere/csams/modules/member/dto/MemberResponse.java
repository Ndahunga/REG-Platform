package rw.terimbere.csams.modules.member.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
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
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MemberResponse {

    private UUID userId;
    private UUID membershipId;
    private UUID cooperativeId;
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private String fullName;
    private String phone;
    private String nationalId;
    private String address;
    private String profileImageKey;
    private String profileImageUrl;
    private AccountStatus accountStatus;
    private String membershipStatus;
    private LocalDate membershipDate;
    private String roleInCooperative;
    private Integer shareCount;
    private Instant createdAt;
    private Instant updatedAt;

    /** Returned only once when a temporary password was generated or supplied at registration. */
    private String temporaryPassword;
}
