package rw.terimbere.csams.modules.membership.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cooperative_memberships")
public class CooperativeMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "cooperative_id", nullable = false)
    private UUID cooperativeId;

    @Builder.Default
    @Column(name = "membership_status", nullable = false, length = 32)
    private String membershipStatus = "ACTIVE";

    @Builder.Default
    @Column(name = "membership_date", nullable = false)
    private LocalDate membershipDate = LocalDate.now();

    @Column(name = "role_in_cooperative", length = 64)
    private String roleInCooperative;

    @Builder.Default
    @Column(name = "share_count", nullable = false)
    private Integer shareCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (version == null) {
            version = 0L;
        }
        if (membershipDate == null) {
            membershipDate = LocalDate.now();
        }
        if (shareCount == null || shareCount < 0) {
            shareCount = 0;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
