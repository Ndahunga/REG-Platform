package rw.terimbere.csams.modules.filemanagement.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.filemanagement.entity.StoredFile;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

    Optional<StoredFile> findByStorageKeyAndDeletedFalse(String storageKey);
}
