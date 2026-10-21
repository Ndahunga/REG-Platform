package rw.terimbere.csams.modules.contribution.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
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
public class ContributionImportPreviewRowResponse {

    private int rowNumber;
    private String username;
    private String memberName;
    private BigDecimal amount;
    private LocalDate paymentDate;
    private String reference;
    private String notes;
    private boolean valid;
    private List<String> errors;
    private UUID memberUserId;
}
