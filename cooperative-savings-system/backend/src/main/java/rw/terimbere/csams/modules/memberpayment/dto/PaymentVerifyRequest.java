package rw.terimbere.csams.modules.memberpayment.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.memberpayment.entity.VerificationMethod;

/**
 * The credible confirmation of received funds that verification (and evidence completion) must carry. There is
 * deliberately no field for receipt analysis or OCR output: unknown fields are rejected, so extracted text cannot be
 * smuggled in as proof.
 */
@Data
@NoArgsConstructor
public class PaymentVerifyRequest {

    @NotNull
    private VerificationMethod method;

    /** What the officer relied on, in words (for example the statement line or the provider confirmation seen). */
    @NotBlank
    @Size(max = 1000)
    private String basis;

    /** External transaction or reconciliation identifier. Required for every method except cash. */
    @Size(max = 128)
    private String reference;

    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown field: " + name);
    }
}
