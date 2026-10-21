package rw.terimbere.csams.modules.membership.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.membership.entity.MemberOpeningBalance;

public interface MemberOpeningBalanceRepository extends JpaRepository<MemberOpeningBalance, UUID> {

    Optional<MemberOpeningBalance> findByCooperativeIdAndMemberUserId(UUID cooperativeId, UUID memberUserId);

    boolean existsByCooperativeIdAndMemberUserId(UUID cooperativeId, UUID memberUserId);

    List<MemberOpeningBalance> findByCooperativeId(UUID cooperativeId);

    @Query("select coalesce(sum(b.openingContributionAmount), 0) from MemberOpeningBalance b"
            + " where b.cooperativeId = :cooperativeId")
    BigDecimal sumOpeningContributions(@Param("cooperativeId") UUID cooperativeId);

    @Query("select coalesce(sum(b.openingShareCapitalAmount), 0) from MemberOpeningBalance b"
            + " where b.cooperativeId = :cooperativeId")
    BigDecimal sumOpeningShareCapital(@Param("cooperativeId") UUID cooperativeId);
}
