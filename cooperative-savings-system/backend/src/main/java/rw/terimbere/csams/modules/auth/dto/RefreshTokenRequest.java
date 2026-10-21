package rw.terimbere.csams.modules.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshTokenRequest {

    /**
     * Optional body refresh token. Phase 2 may prefer an HttpOnly cookie.
     */
    private String refreshToken;
}
