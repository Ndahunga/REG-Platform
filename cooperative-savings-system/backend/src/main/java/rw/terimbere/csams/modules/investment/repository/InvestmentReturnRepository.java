package rw.terimbere.csams.modules.investment.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.investment.entity.InvestmentReturn;

public interface InvestmentReturnRepository extends JpaRepository<InvestmentReturn, UUID> {

    List<InvestmentReturn> findByInvestmentIdAndCooperativeIdOrderByReturnDateDescCreatedAtDesc(
            UUID investmentId, UUID cooperativeId);

    List<InvestmentReturn> findByCooperativeIdAndInvestmentIdAndReturnDateAndAmountTotal(
            UUID cooperativeId, UUID investmentId, java.time.LocalDate returnDate, java.math.BigDecimal amountTotal);

    boolean existsByInvestmentId(UUID investmentId);
}
