package rw.terimbere.csams.modules.cooperative.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.shared.exceptions.BusinessException;

/**
 * Narrow server-side protection while only the accumulating workflows exist.
 *
 * <p>Loan disbursement, payout confirmation and investment activation were built for the accumulating model, where
 * collected funds are retained for lending and investment. A scheme that chose ROTATING or HYBRID must not be treated
 * as accumulating by them, so they are refused for those schemes until their model-specific rules are implemented.
 * Nothing else is blocked: setup, administration, contributions, loan applications and approvals, fines and reports
 * all keep working under every model.
 *
 * <p>Legacy schemes with no recorded model are deliberately not blocked: they already operate with financial history,
 * and refusing lending to every existing scheme the moment this ships would halt real business. They are flagged as
 * "not configured" for an administrator to classify, and model-specific operations introduced later will require it.
 */
@Service
@RequiredArgsConstructor
public class SchemeOperatingModelGuard {

    public static final String UNSUPPORTED_CODE = "OPERATING_MODEL_NOT_SUPPORTED";

    private final CooperativeRepository cooperativeRepository;

    /** Reads the scheme's immutable model (a plain read; it never changes once set) and refuses unsupported ones. */
    public void requireAccumulatingWorkflows(UUID cooperativeId, String operation) {
        SchemeOperatingModel model = cooperativeRepository.findOperatingModelById(cooperativeId);
        if (model != null && !model.supportsAccumulatingWorkflows()) {
            throw new BusinessException(
                    UNSUPPORTED_CODE,
                    operation + " is not available yet for saving schemes that use the " + label(model)
                            + " operating model");
        }
    }

    private static String label(SchemeOperatingModel model) {
        return switch (model) {
            case ACCUMULATING -> "Accumulating Savings";
            case ROTATING -> "Rotating Savings";
            case HYBRID -> "Hybrid Savings";
        };
    }
}
