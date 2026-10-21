package rw.terimbere.csams.modules.share.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.share.entity.ShareValuationSnapshot;

public interface ShareValuationSnapshotRepository extends JpaRepository<ShareValuationSnapshot, UUID> {}
