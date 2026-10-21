package rw.terimbere.csams.modules.memberpayment.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.memberpayment.entity.VerificationMethod;

class ProviderCallbackSignatureTest {

    private static final String TS = "1760000000";

    static String sign(String secret, String timestamp, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void onlyTheExactBodyAndTimestampSignedWithTheSecretAreAccepted() throws Exception {
        byte[] body = "{\"eventId\":\"e1\"}".getBytes(StandardCharsets.UTF_8);
        String good = sign("s3cret", TS, body);

        assertThat(ProviderCallbackController.validSignature("s3cret", TS, body, good)).isTrue();
        assertThat(ProviderCallbackController.validSignature("s3cret", TS, body, "sha256=" + good.toUpperCase())).isTrue();
        assertThat(ProviderCallbackController.validSignature("other", TS, body, good)).isFalse();
        assertThat(ProviderCallbackController.validSignature("s3cret", TS, "{\"eventId\":\"e2\"}".getBytes(StandardCharsets.UTF_8), good)).isFalse();
        assertThat(ProviderCallbackController.validSignature("s3cret", "1760000001", body, good)).as("the timestamp is signed").isFalse();
        assertThat(ProviderCallbackController.validSignature("s3cret", TS, body, null)).isFalse();
        assertThat(ProviderCallbackController.validSignature("s3cret", TS, body, "")).isFalse();
        assertThat(ProviderCallbackController.validSignature("s3cret", TS, body, "deadbeef")).isFalse();
    }

    @Test
    void aChannelWithoutAConfiguredSecretHasNoAutomaticConfirmation() {
        ProviderCallbackProperties properties = new ProviderCallbackProperties();
        assertThat(properties.anyConfigured()).isFalse();
        assertThat(properties.secretFor("MTN_MOMO")).isNull();

        properties.setSecrets(Map.of("mtn_momo", "abc", "AIRTEL_MONEY", " "));
        assertThat(properties.secretFor("MTN_MOMO")).isEqualTo("abc");
        assertThat(properties.secretFor("AIRTEL_MONEY")).as("a blank secret is no secret").isNull();
        assertThat(properties.anyConfigured()).isTrue();
    }

    @Test
    void sourcesMapToTheMethodThatTheyCanConfirmAndOcrOrReferenceMatchingNeverRecordTransactions() {
        assertThat(TransactionMatchingService.methodOf(VerificationSource.BANK_STATEMENT_IMPORT)).isEqualTo(VerificationMethod.BANK_RECONCILED);
        assertThat(TransactionMatchingService.methodOf(VerificationSource.MOBILE_MONEY_STATEMENT_IMPORT)).isEqualTo(VerificationMethod.MOBILE_MONEY_RECONCILED);
        assertThat(TransactionMatchingService.methodOf(VerificationSource.PROVIDER_CALLBACK)).isEqualTo(VerificationMethod.PROVIDER_CONFIRMED);
        assertThat(VerificationSource.RECEIPT_OCR.authoritative()).isFalse();
        assertThat(VerificationSource.REFERENCE_MATCH.authoritative()).isFalse();
        assertThat(VerificationSource.RECEIPT_OCR.recordsTransactions()).isFalse();
        assertThat(VerificationSource.MANUAL_CASH_CONFIRMATION.recordsTransactions()).isFalse();
    }
}
