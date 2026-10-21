package rw.terimbere.csams.modules.contact.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Public "contact us" message. The destination is never part of the request: it always goes to the configured
 * support mailbox. No field may contain a line break, so nothing here can reach a mail header as an injection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContactRequest {

    public static final int NAME_MAX = 80;
    public static final int EMAIL_MAX = 255;
    public static final int PHONE_MAX = 20;
    public static final int MESSAGE_MAX = 2000;

    @NotBlank(message = "First name is required")
    @Size(max = NAME_MAX, message = "First name must be at most 80 characters")
    @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "First name contains invalid characters")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = NAME_MAX, message = "Last name must be at most 80 characters")
    @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Last name contains invalid characters")
    private String lastName;

    @NotBlank(message = "Email is required")
    @Email(message = "Enter a valid email address")
    @Size(max = EMAIL_MAX, message = "Email must be at most 255 characters")
    @Pattern(regexp = "^[^\\s<>,;\"\\p{Cntrl}]*$", message = "Enter a valid email address")
    private String email;

    @NotBlank(message = "Country code is required")
    @Pattern(regexp = "^\\+[0-9]{1,4}$", message = "Enter a valid country code such as +250")
    private String countryCode;

    /** Optional. Digits and common separators only. */
    @Size(max = PHONE_MAX, message = "Phone number must be at most 20 characters")
    @Pattern(regexp = "^[0-9 ()+\\-]*$", message = "Enter a valid phone number")
    private String phoneNumber;

    @NotBlank(message = "Message is required")
    @Size(max = MESSAGE_MAX, message = "Message must be at most 2000 characters")
    private String message;
}
