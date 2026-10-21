package rw.terimbere.csams.shared.file;

import java.io.InputStream;
import java.nio.file.Path;

public interface FileStorageService {

    /**
     * Stores content under a relative storage key and returns the normalized key.
     */
    String store(String relativeKey, InputStream content, long contentLength, String contentType);

    /**
     * Loads a previously stored file by relative key.
     */
    Path load(String relativeKey);

    /**
     * Deletes a stored file by relative key if it exists.
     */
    void delete(String relativeKey);

    /**
     * Returns a public or application-relative URL for the stored file key.
     */
    String getPublicUrl(String relativeKey);
}
