package rw.terimbere.csams.modules.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import rw.terimbere.csams.modules.audit.dto.AuditLogResponse;
import rw.terimbere.csams.modules.audit.entity.AuditLog;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.entity.ContributionImport;
import rw.terimbere.csams.modules.contribution.repository.ContributionImportRepository;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.filemanagement.entity.StoredFile;
import rw.terimbere.csams.modules.filemanagement.repository.StoredFileRepository;
import rw.terimbere.csams.modules.fine.entity.Fine;
import rw.terimbere.csams.modules.fine.entity.FinePayment;
import rw.terimbere.csams.modules.fine.repository.FinePaymentRepository;
import rw.terimbere.csams.modules.fine.repository.FineRepository;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseTransaction;
import rw.terimbere.csams.modules.incomeexpense.repository.IncomeExpenseTransactionRepository;
import rw.terimbere.csams.modules.investment.entity.Investment;
import rw.terimbere.csams.modules.investment.entity.InvestmentReturn;
import rw.terimbere.csams.modules.investment.repository.InvestmentRepository;
import rw.terimbere.csams.modules.investment.repository.InvestmentReturnRepository;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanGuarantor;
import rw.terimbere.csams.modules.loan.repository.LoanGuarantorRepository;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.loanrepayment.entity.LoanRepayment;
import rw.terimbere.csams.modules.loanrepayment.repository.LoanRepaymentRepository;
import rw.terimbere.csams.modules.payout.entity.PayoutRun;
import rw.terimbere.csams.modules.payout.repository.PayoutRunRepository;
import rw.terimbere.csams.modules.socialfund.entity.SocialContribution;
import rw.terimbere.csams.modules.socialfund.entity.SocialDisbursement;
import rw.terimbere.csams.modules.socialfund.repository.SocialContributionRepository;
import rw.terimbere.csams.modules.socialfund.repository.SocialDisbursementRepository;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialContribution;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialContributionCampaign;
import rw.terimbere.csams.modules.specialcontribution.repository.SpecialContributionCampaignRepository;
import rw.terimbere.csams.modules.specialcontribution.repository.SpecialContributionRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;

/**
 * Resolves audit actor names and human entity labels in batch so list pages do not N+1.
 */
@Component
@RequiredArgsConstructor
public class AuditLogLabelResolver {

    private final UserRepository userRepository;
    private final CooperativeRepository cooperativeRepository;
    private final FineRepository fineRepository;
    private final FinePaymentRepository finePaymentRepository;
    private final LoanRepository loanRepository;
    private final LoanRepaymentRepository loanRepaymentRepository;
    private final LoanGuarantorRepository loanGuarantorRepository;
    private final ContributionRepository contributionRepository;
    private final ContributionImportRepository contributionImportRepository;
    private final SpecialContributionRepository specialContributionRepository;
    private final SpecialContributionCampaignRepository specialContributionCampaignRepository;
    private final InvestmentRepository investmentRepository;
    private final InvestmentReturnRepository investmentReturnRepository;
    private final IncomeExpenseTransactionRepository incomeExpenseTransactionRepository;
    private final StoredFileRepository storedFileRepository;
    private final PayoutRunRepository payoutRunRepository;
    private final SocialContributionRepository socialContributionRepository;
    private final SocialDisbursementRepository socialDisbursementRepository;
    private final ObjectMapper objectMapper;

    public List<AuditLogResponse> toResponses(List<AuditLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return List.of();
        }
        Context ctx = loadContext(logs);
        List<AuditLogResponse> out = new ArrayList<>(logs.size());
        for (AuditLog log : logs) {
            out.add(toResponse(log, ctx));
        }
        return out;
    }

    public AuditLogResponse toResponse(AuditLog log) {
        List<AuditLogResponse> mapped = toResponses(List.of(log));
        return mapped.isEmpty() ? null : mapped.get(0);
    }

    private AuditLogResponse toResponse(AuditLog log, Context ctx) {
        User actor = log.getUserId() == null ? null : ctx.users.get(log.getUserId());
        return AuditLogResponse.builder()
                .id(log.getId())
                .userId(log.getUserId())
                .userName(displayName(actor))
                .cooperativeId(log.getCooperativeId())
                .action(log.getAction())
                .entityType(log.getEntityType())
                .entityId(log.getEntityId())
                .entityLabel(entityLabel(log, ctx))
                .previousValues(log.getPreviousValues())
                .newValues(log.getNewValues())
                .ipAddress(log.getIpAddress())
                .userAgent(log.getUserAgent())
                .createdAt(log.getCreatedAt())
                .build();
    }

    private Context loadContext(List<AuditLog> logs) {
        Set<UUID> userIds = new HashSet<>();
        for (AuditLog log : logs) {
            if (log.getUserId() != null) {
                userIds.add(log.getUserId());
            }
            if ("User".equals(log.getEntityType()) && log.getEntityId() != null) {
                userIds.add(log.getEntityId());
            }
        }

        Map<UUID, Cooperative> cooperatives =
                loadMap(cooperativeRepository, entityIds(logs, "Cooperative"), Cooperative::getId);
        Map<UUID, Fine> fines = loadMap(fineRepository, entityIds(logs, "Fine"), Fine::getId);
        Map<UUID, FinePayment> finePayments =
                loadMap(finePaymentRepository, entityIds(logs, "FinePayment"), FinePayment::getId);
        Map<UUID, Loan> loans = loadMap(loanRepository, entityIds(logs, "Loan"), Loan::getId);
        Map<UUID, LoanRepayment> repayments =
                loadMap(loanRepaymentRepository, entityIds(logs, "LoanRepayment"), LoanRepayment::getId);
        Map<UUID, LoanGuarantor> guarantors =
                loadMap(loanGuarantorRepository, entityIds(logs, "LoanGuarantor"), LoanGuarantor::getId);
        Map<UUID, Contribution> contributions =
                loadMap(contributionRepository, entityIds(logs, "Contribution"), Contribution::getId);
        Map<UUID, ContributionImport> imports =
                loadMap(contributionImportRepository, entityIds(logs, "ContributionImport"), ContributionImport::getId);
        Map<UUID, SpecialContribution> specials =
                loadMap(specialContributionRepository, entityIds(logs, "SpecialContribution"), SpecialContribution::getId);
        Map<UUID, SpecialContributionCampaign> campaigns = loadMap(
                specialContributionCampaignRepository,
                entityIds(logs, "SpecialContributionCampaign"),
                SpecialContributionCampaign::getId);
        Map<UUID, InvestmentReturn> returns =
                loadMap(investmentReturnRepository, entityIds(logs, "InvestmentReturn"), InvestmentReturn::getId);
        Map<UUID, IncomeExpenseTransaction> incomeExpenses = loadMap(
                incomeExpenseTransactionRepository,
                entityIds(logs, "IncomeExpenseTransaction"),
                IncomeExpenseTransaction::getId);
        Map<UUID, StoredFile> files = loadMap(storedFileRepository, entityIds(logs, "StoredFile"), StoredFile::getId);
        Map<UUID, PayoutRun> payouts = loadMap(payoutRunRepository, entityIds(logs, "PayoutRun"), PayoutRun::getId);
        Map<UUID, SocialContribution> socialContributions = loadMap(
                socialContributionRepository, entityIds(logs, "SocialContribution"), SocialContribution::getId);
        Map<UUID, SocialDisbursement> socialDisbursements = loadMap(
                socialDisbursementRepository, entityIds(logs, "SocialDisbursement"), SocialDisbursement::getId);

        Set<UUID> investmentIds = entityIds(logs, "Investment");
        for (InvestmentReturn ret : returns.values()) {
            if (ret.getInvestmentId() != null) {
                investmentIds.add(ret.getInvestmentId());
            }
        }
        Map<UUID, Investment> investments = loadMap(investmentRepository, investmentIds, Investment::getId);

        collectMember(userIds, fines.values(), Fine::getMemberUserId);
        collectMember(userIds, finePayments.values(), FinePayment::getMemberUserId);
        collectMember(userIds, loans.values(), Loan::getMemberUserId);
        collectMember(userIds, repayments.values(), LoanRepayment::getMemberUserId);
        collectMember(userIds, guarantors.values(), LoanGuarantor::getGuarantorUserId);
        collectMember(userIds, contributions.values(), Contribution::getMemberUserId);
        collectMember(userIds, specials.values(), SpecialContribution::getMemberUserId);
        collectMember(userIds, socialContributions.values(), SocialContribution::getMemberUserId);
        collectMember(userIds, socialDisbursements.values(), SocialDisbursement::getBeneficiaryMemberUserId);

        Map<UUID, User> users = loadMap(userRepository, userIds, User::getId);
        return new Context(
                users,
                cooperatives,
                fines,
                finePayments,
                loans,
                repayments,
                guarantors,
                contributions,
                imports,
                specials,
                campaigns,
                investments,
                returns,
                incomeExpenses,
                files,
                payouts,
                socialContributions,
                socialDisbursements);
    }

    private String entityLabel(AuditLog log, Context ctx) {
        String type = log.getEntityType();
        UUID id = log.getEntityId();
        if (type == null) {
            return fromJson(log);
        }
        String resolved =
                switch (type) {
                    case "User" -> ctx.userName(id);
                    case "Cooperative" -> nameOf(ctx.cooperatives.get(id), Cooperative::getName);
                    case "Fine" -> ctx.userName(memberId(ctx.fines.get(id), Fine::getMemberUserId));
                    case "FinePayment" ->
                        ctx.userName(memberId(ctx.finePayments.get(id), FinePayment::getMemberUserId));
                    case "Loan" -> ctx.userName(memberId(ctx.loans.get(id), Loan::getMemberUserId));
                    case "LoanRepayment" ->
                        ctx.userName(memberId(ctx.repayments.get(id), LoanRepayment::getMemberUserId));
                    case "LoanGuarantor" ->
                        ctx.userName(memberId(ctx.guarantors.get(id), LoanGuarantor::getGuarantorUserId));
                    case "Contribution" ->
                        ctx.userName(memberId(ctx.contributions.get(id), Contribution::getMemberUserId));
                    case "SpecialContribution" ->
                        ctx.userName(memberId(ctx.specials.get(id), SpecialContribution::getMemberUserId));
                    case "SpecialContributionCampaign" ->
                        nameOf(ctx.campaigns.get(id), SpecialContributionCampaign::getName);
                    case "Investment" -> nameOf(ctx.investments.get(id), Investment::getName);
                    case "InvestmentReturn" -> investmentReturnLabel(id, ctx);
                    case "IncomeExpenseTransaction" -> incomeExpenseLabel(ctx.incomeExpenses.get(id));
                    case "StoredFile" -> nameOf(ctx.files.get(id), StoredFile::getOriginalFilename);
                    case "PayoutRun" -> payoutLabel(ctx.payouts.get(id));
                    case "SocialContribution" ->
                        ctx.userName(memberId(ctx.socialContributions.get(id), SocialContribution::getMemberUserId));
                    case "SocialDisbursement" ->
                        ctx.userName(memberId(
                                ctx.socialDisbursements.get(id), SocialDisbursement::getBeneficiaryMemberUserId));
                    case "ContributionImport" -> importLabel(ctx.imports.get(id));
                    default -> null;
                };
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        return fromJson(log);
    }

    private String investmentReturnLabel(UUID returnId, Context ctx) {
        InvestmentReturn ret = ctx.returns.get(returnId);
        if (ret == null || ret.getInvestmentId() == null) {
            return null;
        }
        return nameOf(ctx.investments.get(ret.getInvestmentId()), Investment::getName);
    }

    private static String incomeExpenseLabel(IncomeExpenseTransaction tx) {
        if (tx == null) {
            return null;
        }
        if (tx.getDescription() != null && !tx.getDescription().isBlank()) {
            return tx.getDescription();
        }
        if (tx.getReference() != null && !tx.getReference().isBlank()) {
            return tx.getReference();
        }
        return tx.getCategory() == null ? null : tx.getCategory().name();
    }

    private static String payoutLabel(PayoutRun run) {
        if (run == null) {
            return null;
        }
        if (run.getName() != null && !run.getName().isBlank()) {
            return run.getName();
        }
        if (run.getPeriodFrom() != null && run.getPeriodTo() != null) {
            return run.getPeriodFrom() + " to " + run.getPeriodTo();
        }
        return null;
    }

    private static String importLabel(ContributionImport imported) {
        if (imported == null) {
            return null;
        }
        if (imported.getOriginalFilename() != null && !imported.getOriginalFilename().isBlank()) {
            return imported.getOriginalFilename();
        }
        return imported.getYear() + "-" + String.format("%02d", imported.getMonth());
    }

    private String fromJson(AuditLog log) {
        String json = log.getNewValues();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.hasNonNull("reportType")) {
                return node.get("reportType").asText().replace('_', ' ');
            }
            if (node.has("year") && !node.get("year").isNull() && node.has("month") && !node.get("month").isNull()) {
                return node.get("year").asInt() + "-" + String.format("%02d", node.get("month").asInt());
            }
            if (node.hasNonNull("name")) {
                return node.get("name").asText();
            }
            if (node.hasNonNull("originalFilename")) {
                return node.get("originalFilename").asText();
            }
            if (node.hasNonNull("description")) {
                return node.get("description").asText();
            }
            if (node.hasNonNull("reference")) {
                return node.get("reference").asText();
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static String displayName(User user) {
        if (user == null) {
            return null;
        }
        String full = user.getFullName();
        if (full != null && !full.isBlank()) {
            return full.trim();
        }
        return user.getUsername();
    }

    private static Set<UUID> entityIds(List<AuditLog> logs, String type) {
        Set<UUID> ids = new HashSet<>();
        for (AuditLog log : logs) {
            if (type.equals(log.getEntityType()) && log.getEntityId() != null) {
                ids.add(log.getEntityId());
            }
        }
        return ids;
    }

    private static <T> Map<UUID, T> loadMap(
            JpaRepository<T, UUID> repository, Set<UUID> ids, Function<T, UUID> idFn) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, T> map = new HashMap<>();
        for (T entity : repository.findAllById(ids)) {
            UUID id = idFn.apply(entity);
            if (id != null) {
                map.put(id, entity);
            }
        }
        return map;
    }

    private static <T> void collectMember(Set<UUID> userIds, Iterable<T> rows, Function<T, UUID> memberId) {
        for (T row : rows) {
            UUID id = memberId.apply(row);
            if (id != null) {
                userIds.add(id);
            }
        }
    }

    private static <T> UUID memberId(T entity, Function<T, UUID> getter) {
        return entity == null ? null : getter.apply(entity);
    }

    private static <T> String nameOf(T entity, Function<T, String> getter) {
        if (entity == null) {
            return null;
        }
        String value = getter.apply(entity);
        return value == null || value.isBlank() ? null : value;
    }

    private record Context(
            Map<UUID, User> users,
            Map<UUID, Cooperative> cooperatives,
            Map<UUID, Fine> fines,
            Map<UUID, FinePayment> finePayments,
            Map<UUID, Loan> loans,
            Map<UUID, LoanRepayment> repayments,
            Map<UUID, LoanGuarantor> guarantors,
            Map<UUID, Contribution> contributions,
            Map<UUID, ContributionImport> imports,
            Map<UUID, SpecialContribution> specials,
            Map<UUID, SpecialContributionCampaign> campaigns,
            Map<UUID, Investment> investments,
            Map<UUID, InvestmentReturn> returns,
            Map<UUID, IncomeExpenseTransaction> incomeExpenses,
            Map<UUID, StoredFile> files,
            Map<UUID, PayoutRun> payouts,
            Map<UUID, SocialContribution> socialContributions,
            Map<UUID, SocialDisbursement> socialDisbursements) {

        String userName(UUID userId) {
            return displayName(userId == null ? null : users.get(userId));
        }
    }
}
