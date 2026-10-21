package rw.terimbere.csams.modules.auth.config;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.role.entity.Role;
import rw.terimbere.csams.modules.role.repository.RoleRepository;
import rw.terimbere.csams.modules.user.entity.AccountStatus;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;

/**
 * Optional seed of a default SUPER_ADMIN. Disabled by default — enable only for tests
 * via {@code app.seed.default-admin=true}. Prefer {@code POST /api/v1/auth/bootstrap} for first admin.
 */
@Component
@Order(50)
@ConditionalOnProperty(name = "app.seed.default-admin", havingValue = "true")
@RequiredArgsConstructor
public class DefaultAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultAdminInitializer.class);

    public static final String DEFAULT_USERNAME = "superadmin";
    public static final String DEFAULT_EMAIL = "superadmin@terimbere.local";
    public static final String DEFAULT_PASSWORD = "ChangeMe@123!";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.countByDeletedFalse() > 0) {
            return;
        }

        Role superAdmin = roleRepository
                .findByCode("SUPER_ADMIN")
                .orElseThrow(() -> new IllegalStateException("SUPER_ADMIN role not seeded"));

        User admin = User.builder()
                .username(DEFAULT_USERNAME)
                .email(DEFAULT_EMAIL)
                .passwordHash(passwordEncoder.encode(DEFAULT_PASSWORD))
                .firstName("System")
                .lastName("Administrator")
                .accountStatus(AccountStatus.ACTIVE)
                .failedLoginAttempts(0)
                .roles(new java.util.HashSet<>(Set.of(superAdmin)))
                .build();

        userRepository.save(admin);
        log.warn(
                "Default SUPER_ADMIN created (username='{}'). Change the default password immediately.",
                DEFAULT_USERNAME);
    }
}
