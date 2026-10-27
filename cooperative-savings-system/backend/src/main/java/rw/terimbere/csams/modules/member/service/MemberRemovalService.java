package rw.terimbere.csams.modules.member.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.member.dto.MemberResponse;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Removing a member from a Saving Scheme, without ever losing financial history.
 *
 * <ul>
 *   <li><b>Delete</b> (permanent) is possible only for a member with no history at all: nothing in the scheme refers to
 *       them (no contribution, payment, loan, credit, share, fine, payout, rotation position or opening balance), nothing
 *       they did is on record (no row of any table points at them, audit included), and they belong to no other scheme.
 *       The backend decides this from the database itself, not from the screen.
 *   <li><b>Deactivate</b> takes the member out of active membership of the scheme. Every record stays exactly as it was:
 *       a loan is still owed and still repayable by an officer, contribution arrears and rotation obligations stay on the
 *       books, member credit stays held, and historical reports still include every transaction. Deactivation removes
 *       access and the ability to start new things (payments, loan requests, new obligations); it never forgives anything.
 *   <li><b>Reactivate</b> restores active membership.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class MemberRemovalService {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    /** Tables whose rows for a member are history that must never be deleted: (table, column, whether it has cooperative_id). */
    private record Source(String table, String column, boolean scoped) {}

    private static final List<Source> FINANCIAL_SOURCES = List.of(
            new Source("contributions", "member_user_id", true),
            new Source("special_contributions", "member_user_id", true),
            new Source("member_payments", "member_user_id", true),
            new Source("loans", "member_user_id", true),
            new Source("loan_repayments", "member_user_id", true),
            new Source("loan_guarantors", "guarantor_user_id", true),
            new Source("financial_ledger", "member_user_id", true),
            new Source("member_credit_entries", "member_user_id", true),
            new Source("member_opening_balances", "member_user_id", true),
            new Source("share_purchases", "member_user_id", true),
            new Source("fines", "member_user_id", true),
            new Source("fine_payments", "member_user_id", true),
            new Source("social_contributions", "member_user_id", true),
            new Source("social_disbursements", "beneficiary_member_user_id", true),
            new Source("payout_lines", "member_user_id", true),
            new Source("rotation_periods", "scheduled_recipient_user_id", true),
            new Source("rotation_payouts", "recipient_user_id", true),
            new Source("contribution_import_rows", "member_user_id", false));

    /** Rows that exist only to run the account: removed together with the user, never history. */
    private static final Set<String> DISPOSABLE_TABLES =
            Set.of("user_roles", "refresh_tokens", "password_reset_tokens", "notifications", "cooperative_memberships");

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final UserRepository userRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final MemberService memberService;
    private final AuditService auditService;
    private final Clock clock;
    private final rw.terimbere.csams.shared.erasure.DataEraser eraser;
    private final rw.terimbere.csams.shared.erasure.AccountCleanup accountCleanup;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private volatile List<Source> foreignKeysToUsers;

    public record Impact(
            /** Principal still owed on the member's open loans: stays owed after deactivation. */
            BigDecimal outstandingLoanPrincipal,
            int openLoans,
            /** Unpaid contributions (arrears and future obligations): stay owed after deactivation. */
            BigDecimal unpaidContributions,
            /** Credit the scheme holds for the member: stays held. */
            BigDecimal heldCredit,
            /** Rotation periods still ahead in which the member is the scheduled recipient. */
            int upcomingRecipientPeriods,
            int openPayouts) {}

    public record Eligibility(
            UUID memberUserId,
            String membershipStatus,
            boolean canDelete,
            /** A stable code (MEMBER_HAS_FINANCIAL_HISTORY, ...) when the member cannot be permanently deleted. */
            String deletionBlockedReason,
            /** The kinds of history that block deletion, with how many records each has. */
            List<HistoryBlocker> historyBlockers,
            boolean canDeactivate,
            String deactivationBlockedReason,
            boolean canReactivate,
            Impact impact) {}

    public record HistoryBlocker(String source, long count) {}

    // ------------------------------------------------------------------ eligibility

    @Transactional(readOnly = true)
    public Eligibility eligibility(UUID cooperativeId, UUID memberUserId) {
        UserPrincipal actor = requireLeader(cooperativeId);
        CooperativeMembership membership = requireMembership(cooperativeId, memberUserId);
        User user = userRepository.findByIdAndDeletedFalse(memberUserId).orElseThrow(() -> new ResourceNotFoundException("User", memberUserId));
        return assess(actor, cooperativeId, membership, user);
    }

    private Eligibility assess(UserPrincipal actor, UUID cooperativeId, CooperativeMembership membership, User user) {
        UUID memberId = user.getId();
        List<HistoryBlocker> blockers = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();
        long financial = 0;
        for (Source source : FINANCIAL_SOURCES) {
            long count = count(source, cooperativeId, memberId);
            covered.add(source.table() + "." + source.column());
            if (count > 0) {
                blockers.add(new HistoryBlocker(source.table(), count));
                financial += count;
            }
        }
        long shares = membership.getShareCount() == null ? 0 : membership.getShareCount();
        if (shares > 0) {
            blockers.add(new HistoryBlocker("shares", shares));
            financial += shares;
        }
        long records = 0;
        for (Source fk : foreignKeys()) {
            if (DISPOSABLE_TABLES.contains(fk.table()) || covered.contains(fk.table() + "." + fk.column())) {
                continue;
            }
            long count = count(new Source(fk.table(), fk.column(), false), cooperativeId, memberId);
            if (count > 0) {
                blockers.add(new HistoryBlocker(fk.table(), count));
                records += count;
            }
        }
        long otherSchemes = membershipRepository.findAll().stream()
                .filter(m -> memberId.equals(m.getUserId()) && !cooperativeId.equals(m.getCooperativeId()))
                .count();

        String deletion = null;
        if (memberId.equals(actor.getId())) {
            deletion = "CANNOT_REMOVE_SELF";
        } else if (isProtected(user)) {
            deletion = "PROTECTED_ACCOUNT";
        } else if (financial > 0) {
            deletion = "MEMBER_HAS_FINANCIAL_HISTORY";
        } else if (records > 0) {
            deletion = "MEMBER_HAS_ACTIVITY_RECORDS";
        } else if (otherSchemes > 0) {
            deletion = "MEMBER_IN_OTHER_SCHEMES";
        }
        Impact impact = impact(cooperativeId, memberId);
        String status = membership.getMembershipStatus() == null ? "" : membership.getMembershipStatus().toUpperCase(Locale.ROOT);
        String deactivation = null;
        if (memberId.equals(actor.getId())) {
            deactivation = "CANNOT_REMOVE_SELF";
        } else if (isProtected(user)) {
            deactivation = "PROTECTED_ACCOUNT";
        } else if ("INACTIVE".equals(status)) {
            deactivation = "MEMBER_ALREADY_INACTIVE";
        } else if (impact.openPayouts() > 0) {
            deactivation = "MEMBER_HAS_OPEN_PAYOUT";
        }
        boolean canReactivate = "INACTIVE".equals(status) && !memberId.equals(actor.getId()) && !isProtected(user);
        return new Eligibility(
                memberId, status, deletion == null, deletion, blockers, deactivation == null, deactivation, canReactivate, impact);
    }

    // ------------------------------------------------------------------ actions

    /** Takes the member out of active membership. Every record stays; nothing owed is forgiven. */
    @Transactional
    public MemberResponse deactivate(UUID cooperativeId, UUID memberUserId, String reason, HttpServletRequest http) {
        UserPrincipal actor = requireLeader(cooperativeId);
        String clean = cleanReason(reason);
        CooperativeMembership membership = requireMembership(cooperativeId, memberUserId);
        User user = userRepository.findByIdAndDeletedFalse(memberUserId).orElseThrow(() -> new ResourceNotFoundException("User", memberUserId));
        Eligibility eligibility = assess(actor, cooperativeId, membership, user);
        if (!eligibility.canDeactivate()) {
            throw new ConflictException(eligibility.deactivationBlockedReason(), "This member cannot be deactivated");
        }
        return memberService.changeMembershipStatus(
                cooperativeId, memberUserId, "INACTIVE", clean, AuditableAction.MEMBER_DEACTIVATE, http);
    }

    @Transactional
    public MemberResponse reactivate(UUID cooperativeId, UUID memberUserId, HttpServletRequest http) {
        UserPrincipal actor = requireLeader(cooperativeId);
        CooperativeMembership membership = requireMembership(cooperativeId, memberUserId);
        User user = userRepository.findByIdAndDeletedFalse(memberUserId).orElseThrow(() -> new ResourceNotFoundException("User", memberUserId));
        Eligibility eligibility = assess(actor, cooperativeId, membership, user);
        if (!eligibility.canReactivate()) {
            throw new ConflictException("MEMBER_NOT_INACTIVE", "Only an inactive member can be reactivated");
        }
        return memberService.changeMembershipStatus(cooperativeId, memberUserId, "ACTIVE", null, AuditableAction.MEMBER_REACTIVATE, http);
    }

    /** Permanently deletes a member who has no history of any kind. The database is the judge; the screen is not trusted. */
    @Transactional
    public void delete(UUID cooperativeId, UUID memberUserId, HttpServletRequest http) {
        UserPrincipal actor = requireLeader(cooperativeId);
        CooperativeMembership membership = requireMembership(cooperativeId, memberUserId);
        User user = userRepository.findByIdAndDeletedFalse(memberUserId).orElseThrow(() -> new ResourceNotFoundException("User", memberUserId));
        Eligibility eligibility = assess(actor, cooperativeId, membership, user);
        if (!eligibility.canDelete()) {
            throw new ConflictException(
                    eligibility.deletionBlockedReason(),
                    "This member cannot be permanently deleted; deactivate them instead so their history stays available");
        }
        // The audit record names no personal data: the user id is all it keeps once the account is gone.
        auditService.record(
                actor.getId(),
                cooperativeId,
                AuditableAction.MEMBER_DELETE,
                "User",
                memberUserId,
                "{\"membershipStatus\":\"" + eligibility.membershipStatus() + "\"}",
                null,
                clientIp(http),
                http == null ? null : http.getHeader("User-Agent"));
        try {
            for (String table : List.of("notifications", "refresh_tokens", "password_reset_tokens")) {
                jdbc.update("delete from " + table + " where user_id = ?", memberUserId);
            }
            jdbc.update("delete from user_roles where user_id = ?", memberUserId);
            jdbc.update("delete from cooperative_memberships where cooperative_id = ? and user_id = ?", cooperativeId, memberUserId);
            jdbc.update("delete from users where id = ?", memberUserId);
        } catch (DataIntegrityViolationException e) {
            // something still refers to the member that the checks could not see: nothing is deleted
            throw new ConflictException("MEMBER_HAS_ACTIVITY_RECORDS", "This member is still referred to by other records and cannot be deleted");
        }
    }

    // ------------------------------------------------------------------ full deletion (purge)

    /** Columns that say who a row is about (as opposed to who recorded or approved it). */
    private static final List<String> SUBJECT_COLUMNS = List.of(
            "member_user_id", "beneficiary_member_user_id", "guarantor_user_id", "recipient_user_id",
            "new_recipient_user_id", "previous_recipient_user_id", "original_recipient_user_id", "user_id");

    /** Never deleted with a member: the trail of what happened. */
    private static final Set<String> KEPT = Set.of("audit_logs", "data_deletion_records");

    public record PurgePreview(
            UUID memberUserId,
            String username,
            boolean canPurge,
            /** A stable code when the member cannot be fully deleted. */
            String blockedReason,
            /** What blocks it: tables where the member acted as an officer, or months only they receive. */
            List<String> blockedDetails,
            /** The account goes too (the member belongs to no other scheme). */
            boolean accountRemoved,
            long rowsTotal,
            java.util.Map<String, Integer> rowsByTable) {}

    public record PurgeRequest(String reason, String confirmUsername) {}

    private record PurgeAssessment(
            rw.terimbere.csams.shared.erasure.DataEraser.Plan plan,
            java.util.Map<UUID, UUID> reassign,
            String blocked,
            List<String> details) {}

    /**
     * What deleting the member and everything they have in this scheme would remove (nothing is changed). Meant for
     * records that should never have been there, such as a member uploaded with false information.
     */
    @Transactional(readOnly = true)
    public PurgePreview purgePreview(UUID cooperativeId, UUID memberUserId) {
        UserPrincipal actor = requireLeader(cooperativeId);
        requireMembership(cooperativeId, memberUserId);
        User user = userRepository.findByIdAndDeletedFalse(memberUserId).orElseThrow(() -> new ResourceNotFoundException("User", memberUserId));
        PurgeAssessment a = assessPurge(actor, cooperativeId, user);
        return toPreview(cooperativeId, user, a);
    }

    /**
     * Deletes the member and everything they have in this scheme for good: contributions, payments, loans, fines,
     * shares, opening balances, rotation turns and the ledger lines that came from them. The audit trail stays, and a
     * deletion record keeps who did it, when and why. The account goes too when the member belongs to no other scheme.
     */
    @Transactional
    public PurgePreview purge(UUID cooperativeId, UUID memberUserId, PurgeRequest request, HttpServletRequest http) {
        UserPrincipal actor = requireLeader(cooperativeId);
        requireMembership(cooperativeId, memberUserId);
        User user = userRepository.findByIdAndDeletedFalse(memberUserId).orElseThrow(() -> new ResourceNotFoundException("User", memberUserId));
        String reason = request == null || request.reason() == null ? "" : request.reason().trim();
        if (reason.length() < 10) {
            throw new ConflictException("REASON_REQUIRED", "Explain why the member is deleted (at least 10 characters)");
        }
        String typed = request.confirmUsername() == null ? "" : request.confirmUsername().trim();
        if (!typed.equalsIgnoreCase(user.getUsername())) {
            throw new ConflictException("CONFIRM_USERNAME_MISMATCH", "Type the member's username exactly to confirm");
        }
        jdbc.queryForList("select id from cooperatives where id = ? for update", UUID.class, cooperativeId);
        PurgeAssessment a = assessPurge(actor, cooperativeId, user);
        if (a.blocked() != null) {
            throw new ConflictException(a.blocked(), "This member cannot be fully deleted: " + String.join("; ", a.details()));
        }
        PurgePreview before = toPreview(cooperativeId, user, a);
        a.reassign().forEach((period, other) ->
                jdbc.update("update rotation_periods set scheduled_recipient_user_id = ? where id = ?", other, period));
        java.util.Map<String, Integer> deleted = eraser.execute(a.plan());
        boolean accountDeleted = accountCleanup.deleteIfUnused(memberUserId);
        long total = deleted.values().stream().mapToLong(Integer::longValue).sum();
        String counts;
        try {
            counts = objectMapper.writeValueAsString(new java.util.TreeMap<>(deleted));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            counts = null;
        }
        String schemeName = jdbc.queryForList("select name from cooperatives where id = ?", String.class, cooperativeId)
                .stream().findFirst().orElse(null);
        jdbc.update("insert into data_deletion_records (id, kind, scheme_id, scheme_name, subject_user_id, subject_username, reason,"
                        + " rows_deleted, accounts_deleted, deleted_by, deleted_at) values (?, 'MEMBER', ?, ?, ?, ?, ?, ?, ?, ?, now())",
                UUID.randomUUID(), cooperativeId, schemeName, memberUserId, user.getUsername(),
                reason.length() > 500 ? reason.substring(0, 500) : reason, counts, accountDeleted ? 1 : 0, actor.getId());
        auditService.record(
                actor.getId(),
                cooperativeId,
                AuditableAction.MEMBER_PURGE,
                "User",
                memberUserId,
                "{\"username\":\"" + user.getUsername().replace("\"", "'") + "\"}",
                "{\"rowsDeleted\":" + total + ",\"accountDeleted\":" + accountDeleted + "}",
                clientIp(http),
                http == null ? null : http.getHeader("User-Agent"));
        return before;
    }

    private PurgePreview toPreview(UUID cooperativeId, User user, PurgeAssessment a) {
        Long others = jdbc.queryForObject("select count(*) from cooperative_memberships where user_id = ? and cooperative_id <> ?",
                Long.class, user.getId(), cooperativeId);
        java.util.Map<String, Integer> counts = a.plan().counts();
        long total = counts.values().stream().mapToLong(Integer::longValue).sum();
        return new PurgePreview(user.getId(), user.getUsername(), a.blocked() == null, a.blocked(), a.details(),
                (others == null || others == 0) && !isProtected(user), total, counts);
    }

    private PurgeAssessment assessPurge(UserPrincipal actor, UUID cooperativeId, User user) {
        UUID member = user.getId();
        rw.terimbere.csams.shared.erasure.DataGraph g = eraser.graph();
        java.util.Map<String, List<UUID>> seeds = new java.util.LinkedHashMap<>();
        java.util.Map<String, List<UUID>> emptied = new java.util.LinkedHashMap<>();
        for (String table : g.tables()) {
            if (KEPT.contains(table) || !g.has(table, "cooperative_id") || !g.has(table, "id")) {
                continue;
            }
            for (String column : SUBJECT_COLUMNS) {
                if (!g.has(table, column)) {
                    continue;
                }
                List<UUID> ids = jdbc.queryForList(
                        "select id from " + table + " where " + column + " = ? and cooperative_id = ?", UUID.class, member, cooperativeId);
                if (ids.isEmpty()) {
                    continue;
                }
                // A row about someone else that only names this member in passing keeps its place, without the name.
                if ("original_recipient_user_id".equals(column) && g.nullable(table, column)) {
                    emptied.computeIfAbsent(table + "." + column, k -> new ArrayList<>()).addAll(ids);
                } else {
                    seeds.computeIfAbsent(table, k -> new ArrayList<>()).addAll(ids);
                }
            }
        }
        rw.terimbere.csams.shared.erasure.DataEraser.Plan plan = eraser.plan(seeds);
        emptied.forEach((key, ids) -> {
            String[] parts = key.split("\\.");
            plan.empty(parts[0], parts[1], ids);
        });

        List<String> details = new ArrayList<>();
        String blocked = null;
        if (member.equals(actor.getId())) {
            blocked = "CANNOT_REMOVE_SELF";
        } else if (isProtected(user)) {
            blocked = "PROTECTED_ACCOUNT";
        }
        // Rows this member recorded or approved for others stay: deleting the member would leave them unexplained.
        if (blocked == null) {
            for (rw.terimbere.csams.shared.erasure.DataGraph.Ref ref : g.refsTo("users")) {
                if (KEPT.contains(ref.child()) || SUBJECT_COLUMNS.contains(ref.column())
                        || !g.has(ref.child(), "cooperative_id") || !g.has(ref.child(), "id")) {
                    continue;
                }
                List<UUID> ids = jdbc.queryForList("select id from " + ref.child() + " where " + ref.column() + " = ? and cooperative_id = ?",
                        UUID.class, member, cooperativeId);
                long remaining = ids.stream().filter(id -> !plan.deletes(ref.child(), id)).count();
                if (remaining > 0) {
                    details.add(ref.child() + "." + ref.column() + " (" + remaining + ")");
                }
            }
            if (!details.isEmpty()) {
                blocked = "MEMBER_ACTED_AS_OFFICER";
            }
        }
        // A month whose scheduled recipient is this member moves to another member paid that month, or blocks.
        java.util.Map<UUID, UUID> reassign = new java.util.LinkedHashMap<>();
        if (blocked == null && g.has("rotation_periods", "scheduled_recipient_user_id")) {
            List<java.util.Map<String, Object>> periods = jdbc.queryForList(
                    "select id, period_year, period_month from rotation_periods where cooperative_id = ? and scheduled_recipient_user_id = ?",
                    cooperativeId, member);
            for (java.util.Map<String, Object> p : periods) {
                UUID period = (UUID) p.get("id");
                List<UUID> others = g.has("rotation_turns", "rotation_period_id")
                        ? jdbc.queryForList("select member_user_id from rotation_turns where rotation_period_id = ? and member_user_id <> ?"
                                + " order by share_count desc", UUID.class, period, member)
                        : List.of();
                if (others.isEmpty()) {
                    details.add(p.get("period_year") + "-" + String.format("%02d", ((Number) p.get("period_month")).intValue()));
                } else {
                    reassign.put(period, others.get(0));
                }
            }
            if (!details.isEmpty()) {
                blocked = "MEMBER_ONLY_RECIPIENT_OF_MONTH";
            }
        }
        if (blocked == null) {
            List<String> outside = eraser.rowsOutsideScheme(plan, cooperativeId);
            if (!outside.isEmpty()) {
                blocked = "MEMBER_RECORDS_SHARED";
                details.addAll(outside);
            }
        }
        return new PurgeAssessment(plan, reassign, blocked, details);
    }

    // ------------------------------------------------------------------ internals

    private UserPrincipal requireLeader(UUID cooperativeId) {
        UserPrincipal principal = authorizationService.currentPrincipal();
        if (!principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            authorizationService.requireMembership(cooperativeId);
        }
        if (!CooperativeOfficerRoles.isLeadership(principal)
                || !(principal.hasAuthority("MEMBERSHIP_MANAGE") || principal.hasAuthority("USER_WRITE"))) {
            throw new ForbiddenException("President or Vice President required to remove or deactivate a member");
        }
        return principal;
    }

    private CooperativeMembership requireMembership(UUID cooperativeId, UUID memberUserId) {
        // the same answer whether the user does not exist or belongs to another scheme: nothing is revealed
        return membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", memberUserId));
    }

    private static boolean isProtected(User user) {
        return user.getRoleCodes().contains(CooperativeAuthorizationService.SUPER_ADMIN);
    }

    private static String cleanReason(String reason) {
        String clean = StringUtils.hasText(reason) ? reason.trim() : "";
        if (clean.length() < 5) {
            throw new ConflictException("REASON_REQUIRED", "Explain why the member is being deactivated (at least 5 characters)");
        }
        return clean.length() > 200 ? clean.substring(0, 200) : clean;
    }

    private long count(Source source, UUID cooperativeId, UUID memberId) {
        String sql = "select count(*) from " + source.table() + " where " + source.column() + " = ?";
        Long value;
        if (source.scoped()) {
            value = jdbc.queryForObject(sql + " and cooperative_id = ?", Long.class, memberId, cooperativeId);
        } else {
            value = jdbc.queryForObject(sql, Long.class, memberId);
        }
        return value == null ? 0 : value;
    }

    private Impact impact(UUID cooperativeId, UUID memberId) {
        BigDecimal principal = money("select coalesce(sum(outstanding_principal), 0) from loans where cooperative_id = ? and member_user_id = ?"
                + " and status in ('ACTIVE', 'OVERDUE', 'APPROVED')", cooperativeId, memberId);
        Long loans = jdbc.queryForObject("select count(*) from loans where cooperative_id = ? and member_user_id = ?"
                + " and status in ('ACTIVE', 'OVERDUE', 'APPROVED')", Long.class, cooperativeId, memberId);
        BigDecimal unpaid = money("select coalesce(sum(outstanding_amount), 0) from contributions where cooperative_id = ? and member_user_id = ?"
                + " and status not in ('WAIVED', 'CANCELLED')", cooperativeId, memberId);
        BigDecimal credit = money("select coalesce(sum(case when entry_type = 'RECEIVED' then amount else -amount end), 0)"
                + " from member_credit_entries where cooperative_id = ? and member_user_id = ?", cooperativeId, memberId);
        LocalDate today = LocalDate.now(clock.withZone(KIGALI));
        Long periods = jdbc.queryForObject("select count(*) from rotation_periods where cooperative_id = ? and scheduled_recipient_user_id = ?"
                        + " and status = 'SCHEDULED' and (period_year * 100 + period_month) >= ?",
                Long.class, cooperativeId, memberId, today.getYear() * 100 + today.getMonthValue());
        Long open = jdbc.queryForObject("select count(*) from rotation_payouts where cooperative_id = ? and recipient_user_id = ?"
                + " and status in ('REQUESTED', 'APPROVED')", Long.class, cooperativeId, memberId);
        return new Impact(principal, loans == null ? 0 : loans.intValue(), unpaid, credit,
                periods == null ? 0 : periods.intValue(), open == null ? 0 : open.intValue());
    }

    private BigDecimal money(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return MoneyUtils.scaleForStorage(value == null ? BigDecimal.ZERO : value);
    }

    /** Every (table, column) that refers to a user, read from the database itself so a table added later is covered. */
    private List<Source> foreignKeys() {
        List<Source> cached = foreignKeysToUsers;
        if (cached != null) {
            return cached;
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Source> found = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (String name : new String[] {"users", "USERS"}) {
                try (ResultSet rs = connection.getMetaData().getExportedKeys(null, null, name)) {
                    while (rs.next()) {
                        String table = rs.getString("FKTABLE_NAME").toLowerCase(Locale.ROOT);
                        String column = rs.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT);
                        if (seen.add(table + "." + column)) {
                            found.add(new Source(table, column, false));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the references to users", e);
        }
        foreignKeysToUsers = List.copyOf(found);
        return foreignKeysToUsers;
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        return StringUtils.hasText(forwarded) ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
