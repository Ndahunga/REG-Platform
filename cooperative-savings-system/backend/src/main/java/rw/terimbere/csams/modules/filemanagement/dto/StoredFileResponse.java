package rw.terimbere.csams.modules.filemanagement.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoredFileResponse {

    private UUID id;
    private UUID cooperativeId;
    private String originalFilename;
    private String storageKey;
    private String contentType;
    private long sizeBytes;
    private String category;
    private UUID uploadedBy;
    private Instant createdAt;
    /** Relative API path for authenticated download (not a filesystem path). */
    private String downloadUrl;
}
