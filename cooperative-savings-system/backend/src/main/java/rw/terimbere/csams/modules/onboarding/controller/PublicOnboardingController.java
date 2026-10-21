package rw.terimbere.csams.modules.onboarding.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.auth.dto.AuthResult;
import rw.terimbere.csams.modules.auth.dto.LoginResponse;
import rw.terimbere.csams.modules.auth.service.AuthService;
import rw.terimbere.csams.modules.onboarding.dto.PublicOnboardingRequest;
import rw.terimbere.csams.modules.onboarding.service.PublicOnboardingService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/onboarding")
@RequiredArgsConstructor
@Tag(name = "Onboarding", description = "Public customer Saving Scheme self-onboarding")
public class PublicOnboardingController {

    private final PublicOnboardingService publicOnboardingService;
    private final AuthService authService;

    @PostMapping("/signup")
    @Operation(
            summary = "Create a Saving Scheme and President account",
            description =
                    "Public customer onboarding. Always creates a new user as PRESIDENT, a cooperative "
                            + "with COMPLETE onboarding, and a 4-month START_TRIAL subscription. "
                            + "Never assigns SUPER_ADMIN. Distinct from POST /api/v1/auth/signup.")
    public ResponseEntity<ApiResponse<LoginResponse>> signup(
            @Valid @RequestBody PublicOnboardingRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        AuthResult result = publicOnboardingService.signup(request, httpRequest);
        authService.writeRefreshCookie(httpResponse, result.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.ok("Saving Scheme created", result.getResponse()));
    }
}
