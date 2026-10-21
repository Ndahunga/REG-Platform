package rw.terimbere.csams.modules.memberpayment.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;

public interface SchemePaymentAccountRepository extends JpaRepository<SchemePaymentAccount, UUID> {

    List<SchemePaymentAccount> findByCooperativeIdOrderByCreatedAtAsc(UUID cooperativeId);

    List<SchemePaymentAccount> findByCooperativeIdAndActiveTrue(UUID cooperativeId);

    Optional<SchemePaymentAccount> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    Optional<SchemePaymentAccount> findByCooperativeIdAndChannelAndAccountIdentifier(
            UUID cooperativeId, PaymentMethod channel, String accountIdentifier);
}
