package rw.terimbere.csams.modules.historicalimport.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import rw.terimbere.csams.modules.historicalimport.dto.HistoricalImportError;
import rw.terimbere.csams.modules.historicalimport.dto.HistoricalMigrationSummary;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalMigrationSheet;
import rw.terimbere.csams.modules.loan.service.ImportedLoanPositionCalculator;

/** Business-validated migration rows (valid and invalid) with the derived drafts used for persistence. */
final class ValidatedMigration {

    final LocalDate cutoverDate;
    final BigDecimal openingAvailableFunds;
    final List<HistoricalImportError> errors = new ArrayList<>();
    final List<ValidatedRow<MemberDraft>> members = new ArrayList<>();
    final List<ValidatedRow<ShareDraft>> shares = new ArrayList<>();
    final List<ValidatedRow<LoanDraft>> loans = new ArrayList<>();
    HistoricalMigrationSummary summary;

    ValidatedMigration(LocalDate cutoverDate, BigDecimal openingAvailableFunds) {
        this.cutoverDate = cutoverDate;
        this.openingAvailableFunds = openingAvailableFunds;
    }

    record ValidatedRow<T>(
            HistoricalMigrationSheet sheet,
            int rowNumber,
            String sourceKey,
            String fingerprint,
            boolean valid,
            List<HistoricalImportError> errors,
            T draft) {}

    /**
     * @param existingUserId set when the row safely identifies an existing platform user
     *     (username and email, and National ID when given, all belong to that one user)
     */
    record MemberDraft(
            String username,
            String firstName,
            String lastName,
            String email,
            String phone,
            String nationalId,
            BigDecimal openingContribution,
            UUID existingUserId) {

        boolean createUser() {
            return existingUserId == null;
        }
    }

    record ShareDraft(String username, int shareCount, BigDecimal shareCapital) {}

    record LoanDraft(
            String username,
            ImportedLoanPositionCalculator.RepaymentType type,
            BigDecimal loanAmount,
            BigDecimal interestRatePercent,
            LocalDate issuedDate,
            int termMonths,
            BigDecimal amountPaid,
            LocalDate lastPaymentDate,
            ImportedLoanPositionCalculator.Position position) {}

    int totalRows() {
        return members.size() + shares.size() + loans.size();
    }

    int validRows() {
        return (int) allRows().stream().filter(ValidatedRow::valid).count();
    }

    int invalidRows() {
        return totalRows() - validRows();
    }

    boolean hasErrors() {
        return !errors.isEmpty();
    }

    List<ValidatedRow<?>> allRows() {
        List<ValidatedRow<?>> all = new ArrayList<>();
        all.addAll(members);
        all.addAll(shares);
        all.addAll(loans);
        return all;
    }
}
