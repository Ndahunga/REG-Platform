package rw.terimbere.csams.modules.audit.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import rw.terimbere.csams.modules.audit.entity.AuditLog;

public interface AuditLogRepository
        extends JpaRepository<AuditLog, UUID>, JpaSpecificationExecutor<AuditLog> {

    Optional<AuditLog> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    List<AuditLog> findByCooperativeIdAndActionOrderByCreatedAtAsc(UUID cooperativeId, String action);
}
