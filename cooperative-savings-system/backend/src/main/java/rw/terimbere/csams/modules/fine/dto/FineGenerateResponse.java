package rw.terimbere.csams.modules.fine.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FineGenerateResponse {

    private int createdCount;
    private int skippedDuplicates;
    private int skippedNotOverdue;
    private List<FineResponse> created;
}
