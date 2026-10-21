package rw.terimbere.csams.modules.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.auth.dto.AuthResult;
import rw.terimbere.csams.modules.auth.dto.AuthUserResponse;
import rw.terimbere.csams.modules.auth.dto.BootstrapAdminRequest;
import rw.terimbere.csams.modules.auth.dto.ChangePasswordRequest;
import rw.terimbere.csams.modules.auth.dto.LoginRequest;
import rw.terimbere.csams.modules.auth.dto.LoginResponse;
import rw.terimbere.csams.modules.auth.dto.PasswordResetConfirmRequest;
import rw.terimbere.csams.modules.auth.dto.PasswordResetRequest;
import rw.terimbere.csams.modules.auth.dto.RefreshTokenRequest;
import rw.terimbere.csams.modules.auth.dto.SignupRequest;
import rw.terimbere.csams.modules.auth.dto.UpdateProfileRequest;
import rw.terimbere.csams.modules.auth.service.AuthService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Login, refresh, logout, and password management")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/bootstrap")
    @Operation(summary = "Create the first SUPER_ADMIN (only when no users exist)")
    public ResponseEntity<ApiResponse<AuthUserResponse>> bootstrap(
            @Valid @RequestBody BootstrapAdminRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                "First administrator created", authService.bootstrapAdmin(request, httpRequest)));
    }

    @PostMapping("/signup")
    @Operation(
            summary = "Legacy self-registration (always creates a MEMBER, never a SUPER_ADMIN)",
            description = "Saving Scheme sign-up is POST /api/v1/onboarding/signup. System administrators are "
                    + "created by /auth/bootstrap on an empty installation or from the Super Admin portal.")
    public ResponseEntity<ApiResponse<LoginResponse>> signup(
            @Valid @RequestBody SignupRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        AuthResult result = authService.signup(request, httpRequest);
        authService.writeRefreshCookie(httpResponse, result.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.ok("Account created", result.getResponse()));
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate user")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        AuthResult result = authService.login(request, httpRequest);
        authService.writeRefreshCookie(httpResponse, result.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.ok(result.getResponse()));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh access token")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(
            @RequestBody(required = false) RefreshTokenRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        Optional<String> bodyToken = Optional.ofNullable(request).map(RefreshTokenRequest::getRefreshToken);
        AuthResult result = authService.refresh(bodyToken, httpRequest, httpResponse);
        return ResponseEntity.ok(ApiResponse.ok(result.getResponse()));
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout user")
    public ResponseEntity<ApiResponse<Void>> logout(
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        authService.logout(httpRequest, httpResponse);
        return ResponseEntity.ok(ApiResponse.ok("Logged out", null));
    }

    @PostMapping("/change-password")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Change password for the authenticated user")
    public ResponseEntity<ApiResponse<LoginResponse>> changePassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        AuthResult result = authService.changePassword(principal.getId(), request, httpRequest);
        authService.writeRefreshCookie(httpResponse, result.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.ok("Password changed", result.getResponse()));
    }

    @PostMapping("/password-reset/request")
    @Operation(summary = "Request a password reset")
    public ResponseEntity<ApiResponse<Map<String, String>>> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request, HttpServletRequest httpRequest) {
        authService.requestPasswordReset(request, httpRequest);
        return ResponseEntity.ok(ApiResponse.ok(
                authService.getGenericResetMessage(),
                Map.of("message", authService.getGenericResetMessage())));
    }

    @PostMapping("/password-reset/confirm")
    @Operation(summary = "Confirm password reset with token")
    public ResponseEntity<ApiResponse<Void>> confirmPasswordReset(
            @Valid @RequestBody PasswordResetConfirmRequest request, HttpServletRequest httpRequest) {
        authService.confirmPasswordReset(request, httpRequest);
        return ResponseEntity.ok(ApiResponse.ok("Password has been reset", null));
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Current authenticated user profile")
    public ResponseEntity<ApiResponse<AuthUserResponse>> me(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(authService.currentUser(principal.getId())));
    }

    @PatchMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Update current authenticated user profile")
    public ResponseEntity<ApiResponse<AuthUserResponse>> updateMe(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok("Profile updated", authService.updateProfile(principal.getId(), request)));
    }
}
