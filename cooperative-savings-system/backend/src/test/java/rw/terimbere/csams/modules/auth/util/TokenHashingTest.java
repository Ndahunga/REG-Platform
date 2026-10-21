package rw.terimbere.csams.modules.auth.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenHashingTest {

    @Test
    void sha256Hex_isDeterministicAndHexEncoded() {
        String hash1 = TokenHashing.sha256Hex("sample-token");
        String hash2 = TokenHashing.sha256Hex("sample-token");
        String other = TokenHashing.sha256Hex("other-token");

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64);
        assertThat(hash1).matches("[0-9a-f]{64}");
        assertThat(hash1).isNotEqualTo(other);
    }
}
