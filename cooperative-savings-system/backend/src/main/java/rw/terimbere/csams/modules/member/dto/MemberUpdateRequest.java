package rw.terimbere.csams.modules.member.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.shared.validation.RwandanNationalId;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberUpdateRequest {

    @NotBlank
    @Size(max = 128)
    private String firstName;

    @NotBlank
    @Size(max = 128)
    private String lastName;

    @Size(min = 3, max = 64)
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "Username may contain letters, numbers, dots, underscores, and hyphens")
    private String username;

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 32)
    private String phone;

    @RwandanNationalId
    private String nationalId;

    @Size(max = 512)
    private String address;

    @PastOrPresent(message = "Membership date cannot be in the future")
    private LocalDate membershipDate;

    @Size(max = 64)
    private String roleInCooperative;

    /**
     * Ignored on ordinary member update. Owned shares change only through approved SharePurchase
     * (or historical-import opening balances).
     */
    @Min(0)
    @Max(1000)
    private Integer shareCount;

    @AssertTrue(message = "Membership date cannot be before 1950-01-01")
    public boolean isMembershipDateReasonable() {
        return membershipDate == null || !membershipDate.isBefore(LocalDate.of(1950, 1, 1));
    }
}
