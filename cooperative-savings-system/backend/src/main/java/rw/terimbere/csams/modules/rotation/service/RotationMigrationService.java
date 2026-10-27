package rw.terimbere.csams.modules.rotation.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.modules.membership.entity.MemberOpeningBalance;
import rw.terimbere.csams.modules.membership.repository.MemberOpeningBalanceRepository;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.CycleResponse;
import rw.terimbere.csams.modules.rotation.dto.RotationDtos.ShareCycleCreateRequest;
import rw.terimbere.csams.modules.rotation.entity.RotationCycle;
import rw.terimbere.csams.modules.rotation.entity.RotationTurn;
import rw.terimbere.csams.modules.rotation.pool.PoolEntryType;
import rw.terimbere.csams.modules.rotation.pool.RotationPoolEntry;
import rw.terimbere.csams.modules.rotation.pool.RotationPoolEntryRepository;
import rw.terimbere.csams.modules.rotation.repository.RotationTurnRepository;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Brings the rotation state of a scheme that was already running into the platform, after the member opening balances
 * were imported: the split in force, the running cycle with who has been paid and who is next, each member's rotated
 * money paid and payouts received before the cutover, and the rotation cash held at the cutover. From then on members
 * see who is next and what they owe or are owed.
 */
@Service
@RequiredArgsConstructor
public class RotationMigrationService {

    private final RotationAccess access;
    private final RotationConfigurationService configurationService;
    private final RotationCycleService cycleService;
    private final RotationTurnRepository turnRepository;
    private final MemberOpeningBalanceRepository openingRepository;
    private final RotationPoolEntryRepository poolEntryRepository;
    private final SchemeFundLockService fundLockService;
    private final AuditService auditService;
    private final Clock clock;

    public record MemberRotationOpening(
            @NotNull UUID memberUserId,
            /** Rotated money the member paid into the running cycle before the cutover. */
            @NotNull @PositiveOrZero BigDecimal rotatedPaid,
            /** Payouts received before the cutover; defaults to what the cycle's turns say. */
            @PositiveOrZero BigDecimal received) {}

    public record RotationImportRequest(
            @NotNull @Min(2000) @Max(2100) Integer cutoverYear,
            @NotNull @Min(1) @Max(12) Integer cutoverMonth,
            /** Rotated RWF per share (hybrid). Ignored for a rotating scheme (everything rotates). */
            BigDecimal rotatingUnitAmount,
            @NotNull @Valid ShareCycleCreateRequest cycle,
            List<@Valid MemberRotationOpening> members,
            /** Rotation money in the bank at the cutover (paid in, not yet paid out). */
            @PositiveOrZero BigDecimal rotationCashAtCutover) {}

    public record RotationImportResponse(
            CycleResponse cycle,
            BigDecimal totalRotatedPaid,
            BigDecimal totalReceived,
            /** Rotated paid less received: what should be in the bank for the rotation. */
            BigDecimal expectedRotationCash,
            BigDecimal declaredRotationCash,
            BigDecimal difference,
            List<String> warnings) {}

    @Transactional
    public RotationImportResponse importRotation(UUID cooperativeId, RotationImportRequest request, HttpServletRequest http) {
        UserPrincipal principal = access.requireManage(cooperativeId);
        access.requireRotationModel(cooperativeId);
        fundLockService.lockSchemeFund(cooperativeId);

        // The cutover month here is the first month the platform collects (e.g. September for an August cut-off).
        YearMonth cutover = YearMonth.of(request.cutoverYear(), request.cutoverMonth());
        if (cutover.isAfter(access.currentMonth())) {
            throw new BusinessException("CUTOVER_IN_FUTURE", "The first month collected in the app cannot be in the future");
        }
        configurationService.createImported(cooperativeId, cutover, request.rotatingUnitAmount(), principal.getId());
        RotationCycle cycle = cycleService.importShareCycle(cooperativeId, request.cycle(), principal.getId(), cutover);

        Map<UUID, BigDecimal> turnReceived = new HashMap<>();
        for (RotationTurn t : turnRepository.findByCooperativeIdAndCycleId(cooperativeId, cycle.getId())) {
            turnReceived.merge(t.getMemberUserId(), t.getOpeningReceivedAmount(), BigDecimal::add);
        }
        Map<UUID, MemberOpeningBalance> openings = new HashMap<>();
        openingRepository.findByCooperativeId(cooperativeId).forEach(o -> openings.put(o.getMemberUserId(), o));
        List<String> warnings = new java.util.ArrayList<>();
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal received = BigDecimal.ZERO;
        Map<UUID, MemberRotationOpening> given = new HashMap<>();
        if (request.members() != null) {
            request.members().forEach(m -> given.put(m.memberUserId(), m));
        }
        java.util.Set<UUID> all = new java.util.HashSet<>(given.keySet());
        all.addAll(turnReceived.keySet());
        for (UUID member : all) {
            MemberRotationOpening m = given.get(member);
            BigDecimal p = m == null ? BigDecimal.ZERO : MoneyUtils.scaleForStorage(m.rotatedPaid());
            BigDecimal r = m != null && m.received() != null
                    ? MoneyUtils.scaleForStorage(m.received())
                    : MoneyUtils.scaleForStorage(turnReceived.getOrDefault(member, BigDecimal.ZERO));
            MemberOpeningBalance o = openings.get(member);
            if (o == null) {
                if (p.signum() > 0 || r.signum() > 0) {
                    warnings.add("NO_OPENING_BALANCE:" + member);
                }
                continue;
            }
            o.setOpeningRotationPaid(p);
            o.setOpeningRotationReceived(r);
            openingRepository.save(o);
            paid = paid.add(p);
            received = received.add(r);
        }

        BigDecimal declared = request.rotationCashAtCutover() == null ? null
                : MoneyUtils.scaleForStorage(request.rotationCashAtCutover());
        if (declared != null && declared.signum() > 0) {
            if (poolEntryRepository.existsByCooperativeIdAndEntryType(cooperativeId, PoolEntryType.OPENING)) {
                warnings.add("POOL_OPENING_ALREADY_RECORDED");
            } else {
                poolEntryRepository.saveAndFlush(RotationPoolEntry.builder()
                        .cooperativeId(cooperativeId)
                        .entryType(PoolEntryType.OPENING)
                        .amount(declared)
                        .effectiveDate(cutover.atDay(1))
                        .reason("Rotation money held at the migration cutover")
                        .reference("MIGRATION-" + cutover)
                        .openingKey(cooperativeId)
                        .recordedBy(principal.getId())
                        .createdAt(Instant.now(clock))
                        .build());
            }
        }
        BigDecimal expected = MoneyUtils.scaleForStorage(paid.subtract(received));
        BigDecimal difference = declared == null ? null : MoneyUtils.scaleForStorage(declared.subtract(expected));
        if (difference != null && difference.signum() != 0) {
            warnings.add("ROTATION_CASH_DIFFERS");
        }
        if (expected.signum() < 0) {
            throw new BusinessException(
                    "ROTATION_OPENING_NEGATIVE",
                    "More was paid out than was paid in before the cutover; check the amounts received");
        }
        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.ROTATION_CYCLE_CREATE,
                "RotationCycle",
                cycle.getId(),
                null,
                "{\"import\":true,\"cutover\":\"" + cutover + "\",\"rotatedPaid\":\"" + paid.toPlainString()
                        + "\",\"received\":\"" + received.toPlainString() + "\"}",
                RotationAccess.clientIp(http),
                RotationAccess.userAgent(http));
        return new RotationImportResponse(
                cycleService.get(cooperativeId, cycle.getId()),
                MoneyUtils.scaleForStorage(paid),
                MoneyUtils.scaleForStorage(received),
                expected,
                declared,
                difference,
                warnings);
    }
}
