package rw.terimbere.csams.shared.file;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.file-storage")
public class FileStorageProperties {

    /**
     * Storage backend type. Phase 1 supports {@code local}.
     */
    private String type = "local";

    /**
     * Base directory for local file storage.
     */
    private String basePath = "../uploads";

    /**
     * Maximum upload size in megabytes.
     */
    private int maxSizeMb = 10;

    /**
     * Optional public URL prefix for locally stored files.
     */
    private String publicUrlPrefix = "/files";
}
