package rw.terimbere.csams.modules.filemanagement.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.filemanagement.FileCategories;
import rw.terimbere.csams.modules.filemanagement.dto.StoredFileResponse;
import rw.terimbere.csams.modules.filemanagement.entity.StoredFile;
import rw.terimbere.csams.modules.filemanagement.repository.StoredFileRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.file.FileStorageProperties;
import rw.terimbere.csams.shared.file.FileStorageService;

@Service
@RequiredArgsConstructor
public class FileManagementService {

    public static final String CATEGORY_COOPERATIVE_LOGO = FileCategories.COOPERATIVE_LOGO;
    public static final String CATEGORY_PROFILE_IMAGE = FileCategories.PROFILE_IMAGE;

    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp");

    private static final Set<String> DOCUMENT_CONTENT_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE,
            MediaType.IMAGE_PNG_VALUE,
            "image/webp",
            MediaType.APPLICATION_PDF_VALUE,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            ".exe",
            ".bat",
            ".cmd",
            ".com",
            ".msi",
            ".scr",
            ".js",
            ".mjs",
            ".cjs",
            ".jar",
            ".war",
            ".php",
            ".phtml",
            ".sh",
            ".bash",
            ".ps1",
            ".vbs",
            ".wsf",
            ".dll",
            ".so");

    private static final Map<String, String> CONTENT_TYPE_EXTENSION = Map.of(
            MediaType.IMAGE_JPEG_VALUE,
            ".jpg",
            MediaType.IMAGE_PNG_VALUE,
            ".png",
            "image/webp",
            ".webp",
            MediaType.APPLICATION_PDF_VALUE,
            ".pdf",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            ".xlsx");

    private final FileStorageService fileStorageService;
    private final FileStorageProperties fileStorageProperties;
    private final StoredFileRepository storedFileRepository;
    private final AuditService auditService;
    private final CooperativeAuthorizationService authorizationService;

    @Transactional
    public StoredFile storeImage(
            MultipartFile file,
            String category,
            String keyPrefix,
            UUID cooperativeId,
            UUID uploadedBy,
            String ip,
            String userAgent) {
        return store(file, category, keyPrefix, cooperativeId, uploadedBy, ip, userAgent, true);
    }

    /**
     * Stores a cooperative-scoped document (PDF/images/XLSX) with validation and secure key.
     */
    @Transactional
    public StoredFileResponse storeDocument(
            UUID cooperativeId,
            MultipartFile file,
            String category,
            UserPrincipal principal,
            String ip,
            String userAgent) {
        authorizationService.requireMembership(cooperativeId);
        String normalizedCategory =
                StringUtils.hasText(category) ? category.trim().toUpperCase(Locale.ROOT) : FileCategories.GENERAL_DOCUMENT;
        if (!FileCategories.isKnown(normalizedCategory)) {
            throw new ValidationException("Unknown file category");
        }
        if (FileCategories.isImageOnly(normalizedCategory)) {
            throw new ValidationException("Use the dedicated image upload endpoint for logos and profile photos");
        }

        String prefix = "cooperatives/" + cooperativeId + "/" + normalizedCategory.toLowerCase(Locale.ROOT);
        StoredFile saved =
                store(file, normalizedCategory, prefix, cooperativeId, principal.getId(), ip, userAgent, false);
        return toResponse(saved);
    }

    private StoredFile store(
            MultipartFile file,
            String category,
            String keyPrefix,
            UUID cooperativeId,
            UUID uploadedBy,
            String ip,
            String userAgent,
            boolean imageOnly) {
        validateUpload(file, imageOnly);

        String extension = resolveSecureExtension(file);
        String storageKey = keyPrefix + "/" + UUID.randomUUID() + extension;

        try (InputStream inputStream = file.getInputStream()) {
            fileStorageService.store(storageKey, inputStream, file.getSize(), file.getContentType());
        } catch (IOException ex) {
            throw new ValidationException("Failed to read uploaded file");
        }

        String originalName = file.getOriginalFilename() == null ? "upload" + extension : file.getOriginalFilename();
        StoredFile storedFile = StoredFile.builder()
                .cooperativeId(cooperativeId)
                .originalFilename(StringUtils.cleanPath(originalName))
                .storageKey(storageKey)
                .contentType(normalizeContentType(file.getContentType()))
                .sizeBytes(file.getSize())
                .category(category)
                .uploadedBy(uploadedBy)
                .build();

        StoredFile saved = storedFileRepository.save(storedFile);
        auditService.record(
                uploadedBy,
                cooperativeId,
                AuditableAction.FILE_UPLOAD,
                "StoredFile",
                saved.getId(),
                null,
                "{\"storageKey\":\"" + storageKey + "\",\"category\":\"" + category + "\"}",
                ip,
                userAgent);
        return saved;
    }

    @Transactional(readOnly = true)
    public Resource loadAsResourceForPrincipal(String storageKey, UserPrincipal principal) {
        String normalized = normalizeKey(storageKey);
        StoredFile meta = storedFileRepository
                .findByStorageKeyAndDeletedFalse(normalized)
                .orElseThrow(() -> new ResourceNotFoundException("File", normalized));

        requireFileAccess(meta, principal);

        try {
            Path path = fileStorageService.load(normalized);
            Resource resource = new UrlResource(path.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new ResourceNotFoundException("File", normalized);
            }
            return resource;
        } catch (IllegalArgumentException | IOException ex) {
            throw new ResourceNotFoundException("File", normalized);
        }
    }

    /** Legacy load used by internal services; prefers authenticated cooperative-aware download. */
    @Transactional(readOnly = true)
    public Resource loadAsResource(String storageKey) {
        String normalized = normalizeKey(storageKey);
        storedFileRepository.findByStorageKeyAndDeletedFalse(normalized).orElse(null);
        try {
            Path path = fileStorageService.load(normalized);
            Resource resource = new UrlResource(path.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new ResourceNotFoundException("File", normalized);
            }
            return resource;
        } catch (IllegalArgumentException | IOException ex) {
            throw new ResourceNotFoundException("File", normalized);
        }
    }

    @Transactional(readOnly = true)
    public String resolveContentType(String storageKey) {
        String normalized = normalizeKey(storageKey);
        return storedFileRepository
                .findByStorageKeyAndDeletedFalse(normalized)
                .map(StoredFile::getContentType)
                .filter(StringUtils::hasText)
                .orElseGet(() -> {
                    try {
                        Path path = fileStorageService.load(normalized);
                        String probed = Files.probeContentType(path);
                        return StringUtils.hasText(probed) ? probed : MediaType.APPLICATION_OCTET_STREAM_VALUE;
                    } catch (Exception ex) {
                        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
                    }
                });
    }

    @Transactional(readOnly = true)
    public StoredFile requireOwnedFile(String storageKey, UUID cooperativeId) {
        String normalized = normalizeKey(storageKey);
        StoredFile meta = storedFileRepository
                .findByStorageKeyAndDeletedFalse(normalized)
                .orElseThrow(() -> new ResourceNotFoundException("File", normalized));
        if (meta.getCooperativeId() == null || !meta.getCooperativeId().equals(cooperativeId)) {
            throw new ForbiddenException("File does not belong to this cooperative");
        }
        return meta;
    }

    public String getPublicUrl(String storageKey) {
        if (!StringUtils.hasText(storageKey)) {
            return null;
        }
        return "/api/v1/files/" + normalizeKey(storageKey);
    }

    public StoredFileResponse toResponse(StoredFile file) {
        return StoredFileResponse.builder()
                .id(file.getId())
                .cooperativeId(file.getCooperativeId())
                .originalFilename(file.getOriginalFilename())
                .storageKey(file.getStorageKey())
                .contentType(file.getContentType())
                .sizeBytes(file.getSizeBytes())
                .category(file.getCategory())
                .uploadedBy(file.getUploadedBy())
                .createdAt(file.getCreatedAt())
                .downloadUrl(getPublicUrl(file.getStorageKey()))
                .build();
    }

    private void requireFileAccess(StoredFile meta, UserPrincipal principal) {
        if (principal == null) {
            throw new ForbiddenException("Authentication required");
        }
        if (principal.hasRole("SUPER_ADMIN")) {
            return;
        }
        if (meta.getCooperativeId() == null) {
            throw new ForbiddenException("File access denied");
        }
        authorizationService.requireMembership(meta.getCooperativeId());
    }

    private void validateUpload(MultipartFile file, boolean imageOnly) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("File is required");
        }
        long maxBytes = fileStorageProperties.getMaxSizeMb() * 1024L * 1024L;
        if (file.getSize() <= 0) {
            throw new ValidationException("Empty files are not allowed");
        }
        if (file.getSize() > maxBytes) {
            throw new ValidationException(
                    "File exceeds maximum size of " + fileStorageProperties.getMaxSizeMb() + "MB");
        }

        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        for (String blocked : BLOCKED_EXTENSIONS) {
            if (original.endsWith(blocked)) {
                throw new ValidationException("File type is not allowed");
            }
        }

        String contentType = normalizeContentType(file.getContentType());
        Set<String> allowed = imageOnly ? IMAGE_CONTENT_TYPES : DOCUMENT_CONTENT_TYPES;
        if (!allowed.contains(contentType)) {
            throw new ValidationException(
                    imageOnly
                            ? "Only JPEG, PNG, and WebP images are allowed"
                            : "Only PDF, JPEG, PNG, WebP, and XLSX files are allowed");
        }
        requireContentMatchesType(file, contentType);
    }

    /**
     * The first bytes must be those of the declared type. A client-declared content type alone proves nothing, so a file
     * that claims to be an image or a document but is something else is refused, not stored under a trusted extension.
     */
    private static void requireContentMatchesType(MultipartFile file, String contentType) {
        byte[] head = new byte[12];
        int read;
        try (java.io.InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        } catch (java.io.IOException e) {
            throw new ValidationException("The file could not be read");
        }
        boolean matches =
                switch (contentType) {
                    case MediaType.IMAGE_PNG_VALUE -> read >= 4
                            && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
                    case MediaType.IMAGE_JPEG_VALUE -> read >= 3
                            && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
                    case "image/webp" -> read >= 12
                            && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                            && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
                    case MediaType.APPLICATION_PDF_VALUE -> read >= 4
                            && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F';
                    case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> read >= 4
                            && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
                    default -> false;
                };
        if (!matches) {
            throw new ValidationException("The file content does not match its declared type");
        }
    }

    private static String resolveSecureExtension(MultipartFile file) {
        String contentType = normalizeContentType(file.getContentType());
        String mapped = CONTENT_TYPE_EXTENSION.get(contentType);
        if (mapped != null) {
            return mapped;
        }
        throw new ValidationException("Unable to determine a safe file extension");
    }

    private static String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "";
        }
        return contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeKey(String key) {
        if (key == null) {
            return "";
        }
        return key.replace('\\', '/').replaceAll("^/+", "");
    }
}
