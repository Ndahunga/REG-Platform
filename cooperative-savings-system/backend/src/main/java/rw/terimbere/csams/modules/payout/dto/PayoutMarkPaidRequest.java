package rw.terimbere.csams.modules.payout.dto;

import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayoutMarkPaidRequest {

    /** When empty/null, marks the entire run paid. Otherwise only listed lines. */
    private List<UUID> lineIds;
}
