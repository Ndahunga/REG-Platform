package rw.terimbere.csams.shared.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.util.StringUtils;

public class LocalFileStorageService implements FileStorageService {

    private final Path basePath;
    private final String publicUrlPrefix;

    public LocalFileStorageService(FileStorageProperties properties) {
        this.basePath = Path.of(properties.getBasePath()).toAbsolutePath().normalize();
        this.publicUrlPrefix = StringUtils.hasText(properties.getPublicUrlPrefix())
                ? properties.getPublicUrlPrefix().replaceAll("/+$", "")
                : "/files";
        try {
            Files.createDirectories(this.basePath);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to create file storage base path: " + this.basePath, ex);
        }
    }

    @Override
    public String store(String relativeKey, InputStream content, long contentLength, String contentType) {
        Path target = resolveSafePath(relativeKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
            return normalizeKey(relativeKey);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to store file: " + relativeKey, ex);
        }
    }

    @Override
    public Path load(String relativeKey) {
        Path target = resolveSafePath(relativeKey);
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            throw new IllegalArgumentException("File not found: " + relativeKey);
        }
        return target;
    }

    @Override
    public void delete(String relativeKey) {
        Path target = resolveSafePath(relativeKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to delete file: " + relativeKey, ex);
        }
    }

    @Override
    public String getPublicUrl(String relativeKey) {
        return publicUrlPrefix + "/" + normalizeKey(relativeKey);
    }

    private Path resolveSafePath(String relativeKey) {
        if (!StringUtils.hasText(relativeKey)) {
            throw new IllegalArgumentException("Storage key must not be blank");
        }
        String normalized = normalizeKey(relativeKey);
        if (normalized.contains("..")) {
            throw new SecurityException("Path traversal detected in storage key");
        }
        Path resolved = basePath.resolve(normalized).normalize();
        if (!resolved.startsWith(basePath)) {
            throw new SecurityException("Resolved path escapes storage base directory");
        }
        return resolved;
    }

    private String normalizeKey(String relativeKey) {
        return relativeKey.replace('\\', '/').replaceAll("^/+", "");
    }
}
