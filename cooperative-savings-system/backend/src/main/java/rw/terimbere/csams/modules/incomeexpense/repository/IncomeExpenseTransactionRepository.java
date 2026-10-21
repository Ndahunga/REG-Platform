package rw.terimbere.csams.modules.incomeexpense.repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseApprovalStatus;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseCategory;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseTransaction;

public interface IncomeExpenseTransactionRepository
        extends JpaRepository<IncomeExpenseTransaction, UUID>,
                JpaSpecificationExecutor<IncomeExpenseTransaction> {

    Optional<IncomeExpenseTransaction> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    java.util.List<IncomeExpenseTransaction> findByCooperativeIdAndTransactionDateAndCategory(
            UUID cooperativeId, LocalDate transactionDate, IncomeExpenseCategory category);

    default Page<IncomeExpenseTransaction> findFiltered(
            UUID cooperativeId,
            IncomeExpenseCategory category,
            IncomeExpenseApprovalStatus status,
            LocalDate fromDate,
            LocalDate toDate,
            Pageable pageable) {
        Pageable page = pageable == null ? Pageable.unpaged() : pageable;
        return findAll(IncomeExpenseSpecs.filtered(cooperativeId, category, status, fromDate, toDate), page);
    }
}
