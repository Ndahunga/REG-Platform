package rw.terimbere.csams.modules.filemanagement.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.filemanagement.service.FileManagementService;
import rw.terimbere.csams.security.UserPrincipal;

@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
@Tag(name = "Files", description = "Authenticated file download with cooperative access checks")
@SecurityRequirement(name = "bearerAuth")
public class FileController {

    private final FileManagementService fileManagementService;

    @GetMapping("/{*key}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Download a stored file by storage key (cooperative membership required)")
    public ResponseEntity<Resource> download(
            @PathVariable("key") String key, @AuthenticationPrincipal UserPrincipal principal) {
        Resource resource = fileManagementService.loadAsResourceForPrincipal(key, principal);
        String contentType = fileManagementService.resolveContentType(key);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(resource);
    }
}
