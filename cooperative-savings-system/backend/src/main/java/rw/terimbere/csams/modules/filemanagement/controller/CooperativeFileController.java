package rw.terimbere.csams.modules.filemanagement.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import rw.terimbere.csams.modules.filemanagement.dto.StoredFileResponse;
import rw.terimbere.csams.modules.filemanagement.service.FileManagementService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/files")
@RequiredArgsConstructor
@Tag(name = "Cooperative Files", description = "Secure cooperative-scoped document uploads")
@SecurityRequirement(name = "bearerAuth")
public class CooperativeFileController {

    private final FileManagementService fileManagementService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Upload a document for the cooperative (PDF, images, XLSX)")
    public ResponseEntity<ApiResponse<StoredFileResponse>> upload(
            @PathVariable UUID cooperativeId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", required = false) String category,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        StoredFileResponse response = fileManagementService.storeDocument(
                cooperativeId,
                file,
                category,
                principal,
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.ok(ApiResponse.ok("File uploaded", response));
    }
}
