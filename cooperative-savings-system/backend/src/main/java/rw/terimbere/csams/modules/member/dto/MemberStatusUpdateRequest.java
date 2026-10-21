package rw.terimbere.csams.modules.member.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.user.entity.AccountStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberStatusUpdateRequest {

    private AccountStatus accountStatus;
    private String membershipStatus;
}
