package rw.terimbere.csams.modules.memberpayment.receipt;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Deployment-specific receipt wording ({@code app.payments.receipt-vocabulary.extra-success} and so on). The built-in
 * vocabulary always applies; entries here are added to it, never substituted for it.
 */
@Component
@ConfigurationProperties(prefix = "app.payments.receipt-vocabulary")
public class ReceiptVocabularyProperties {

    private List<String> extraSuccess = new ArrayList<>();
    private List<String> extraFailed = new ArrayList<>();
    private List<String> extraPending = new ArrayList<>();
    private List<String> extraNeutral = new ArrayList<>();
    private List<String> extraHistoryMarkers = new ArrayList<>();

    public List<String> getExtraSuccess() {
        return extraSuccess;
    }

    public void setExtraSuccess(List<String> extraSuccess) {
        this.extraSuccess = extraSuccess == null ? new ArrayList<>() : extraSuccess;
    }

    public List<String> getExtraFailed() {
        return extraFailed;
    }

    public void setExtraFailed(List<String> extraFailed) {
        this.extraFailed = extraFailed == null ? new ArrayList<>() : extraFailed;
    }

    public List<String> getExtraPending() {
        return extraPending;
    }

    public void setExtraPending(List<String> extraPending) {
        this.extraPending = extraPending == null ? new ArrayList<>() : extraPending;
    }

    public List<String> getExtraNeutral() {
        return extraNeutral;
    }

    public void setExtraNeutral(List<String> extraNeutral) {
        this.extraNeutral = extraNeutral == null ? new ArrayList<>() : extraNeutral;
    }

    public List<String> getExtraHistoryMarkers() {
        return extraHistoryMarkers;
    }

    public void setExtraHistoryMarkers(List<String> extraHistoryMarkers) {
        this.extraHistoryMarkers = extraHistoryMarkers == null ? new ArrayList<>() : extraHistoryMarkers;
    }

    public ReceiptVocabulary vocabulary() {
        return ReceiptVocabulary.defaults()
                .plus(extraSuccess, extraFailed, extraPending, extraNeutral, extraHistoryMarkers);
    }
}
