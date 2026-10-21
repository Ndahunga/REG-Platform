package rw.terimbere.csams.modules.user.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.user.entity.AccountStatus;
import rw.terimbere.csams.modules.user.entity.User;

public interface UserRepository extends JpaRepository<User, UUID> {

    @EntityGraph(attributePaths = {"roles", "roles.permissions"})
    Optional<User> findByUsernameIgnoreCaseAndDeletedFalse(String username);

    @EntityGraph(attributePaths = {"roles", "roles.permissions"})
    Optional<User> findByEmailIgnoreCaseAndDeletedFalse(String email);

    @EntityGraph(attributePaths = {"roles", "roles.permissions"})
    Optional<User> findWithRolesById(UUID id);

    boolean existsByUsernameIgnoreCaseAndDeletedFalse(String username);

    boolean existsByEmailIgnoreCaseAndDeletedFalse(String email);

    boolean existsByUsernameIgnoreCaseAndDeletedFalseAndIdNot(String username, UUID id);

    boolean existsByEmailIgnoreCaseAndDeletedFalseAndIdNot(String email, UUID id);

    Optional<User> findByNationalIdAndDeletedFalse(String nationalId);

    boolean existsByNationalIdAndDeletedFalse(String nationalId);

    boolean existsByNationalIdAndDeletedFalseAndIdNot(String nationalId, UUID id);

    Optional<User> findByIdAndDeletedFalse(UUID id);

    /** Ids (from {@code userIds}) of non-deleted users holding the global SUPER_ADMIN role. */
    @Query(
            """
            SELECT DISTINCT u.id FROM User u
            JOIN u.roles r
            WHERE u.deleted = false
              AND r.code = 'SUPER_ADMIN'
              AND u.id IN :userIds
            """)
    List<UUID> findSuperAdminIdsAmong(@Param("userIds") Collection<UUID> userIds);

    long countByDeletedFalse();

    /** True when the user exists, is not deleted and has exactly this account status. */
    boolean existsByIdAndDeletedFalseAndAccountStatus(UUID id, AccountStatus accountStatus);

    /** All non-deleted holders of the global SUPER_ADMIN role, oldest first. */
    @EntityGraph(attributePaths = "roles")
    @Query(
            """
            SELECT DISTINCT u FROM User u
            JOIN u.roles r
            WHERE u.deleted = false AND r.code = 'SUPER_ADMIN'
            ORDER BY u.createdAt ASC, u.id ASC
            """)
    List<User> findAllSuperAdmins();

    @Query(
            """
            SELECT COUNT(DISTINCT u.id) FROM User u
            JOIN u.roles r
            WHERE u.deleted = false AND r.code = 'SUPER_ADMIN' AND u.accountStatus = :status
            """)
    long countSuperAdminsWithStatus(@Param("status") AccountStatus status);

    @Query(
            """
            SELECT COUNT(u) > 0 FROM User u
            JOIN u.roles r
            WHERE u.id = :id AND r.code = 'SUPER_ADMIN'
            """)
    boolean hasSuperAdminRole(@Param("id") UUID id);
}
