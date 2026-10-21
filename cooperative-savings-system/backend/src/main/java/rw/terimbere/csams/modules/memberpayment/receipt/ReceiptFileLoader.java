package rw.terimbere.csams.modules.memberpayment.receipt;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import rw.terimbere.csams.modules.filemanagement.service.FileManagementService;
import rw.terimbere.csams.shared.exceptions.BusinessException;

/** Reads a stored receipt into memory with a hard size cap, so no receipt can exhaust the server. */
@Component
@RequiredArgsConstructor
public class ReceiptFileLoader {

    private final FileManagementService fileManagementService;

    @Value("${app.ocr.max-bytes:8388608}")
    private long maxBytes;

    public byte[] load(String storageKey) {
        Resource resource = fileManagementService.loadAsResource(storageKey);
        try (InputStream in = resource.getInputStream()) {
            byte[] data = in.readNBytes((int) maxBytes + 1);
            if (data.length > maxBytes) {
                throw new BusinessException("RECEIPT_TOO_LARGE", "The receipt is larger than the allowed size");
            }
            return data;
        } catch (IOException e) {
            throw new BusinessException("RECEIPT_UNREADABLE", "The receipt could not be read");
        }
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
