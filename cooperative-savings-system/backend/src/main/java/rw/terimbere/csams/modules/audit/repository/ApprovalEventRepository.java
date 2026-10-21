package rw.terimbere.csams.modules.audit.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.audit.entity.ApprovalEvent;

public interface ApprovalEventRepository extends JpaRepository<ApprovalEvent, UUID> {

    List<ApprovalEvent> findByCooperativeIdAndEntityTypeAndEntityIdOrderByCreatedAtAsc(
            UUID cooperativeId, String entityType, UUID entityId);
}
