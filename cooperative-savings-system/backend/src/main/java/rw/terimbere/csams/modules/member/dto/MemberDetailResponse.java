package rw.terimbere.csams.modules.member.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberDetailResponse {

    private MemberResponse member;

    @Builder.Default
    private List<Object> contributions = List.of();

    @Builder.Default
    private List<Object> loans = List.of();

    @Builder.Default
    private List<Object> fines = List.of();

    @Builder.Default
    private List<Object> social = List.of();

    @Builder.Default
    private List<Object> payouts = List.of();
}
