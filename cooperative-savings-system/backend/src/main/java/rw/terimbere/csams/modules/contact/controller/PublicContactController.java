package rw.terimbere.csams.modules.contact.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.contact.dto.ContactRequest;
import rw.terimbere.csams.modules.contact.service.ContactMessageService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/public/contact")
@RequiredArgsConstructor
@Tag(name = "Contact", description = "Public contact form")
public class PublicContactController {

    private final ContactMessageService contactMessageService;

    @PostMapping
    @Operation(
            summary = "Send a message to OuWealth support",
            description =
                    "Public, no login. Emails the support mailbox (always the configured address, never one from the "
                            + "request) with Reply-To set to the visitor's email.")
    public ResponseEntity<ApiResponse<Void>> send(@Valid @RequestBody ContactRequest request) {
        contactMessageService.send(request);
        return ResponseEntity.ok(ApiResponse.ok("Your message has been sent to the OuWealth support team.", null));
    }
}
