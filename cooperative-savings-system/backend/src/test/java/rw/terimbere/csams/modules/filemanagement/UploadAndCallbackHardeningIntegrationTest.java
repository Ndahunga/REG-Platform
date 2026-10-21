package rw.terimbere.csams.modules.filemanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.memberpayment.verification.ProviderCallbackProperties;
import rw.terimbere.csams.testsupport.SchemeFixtures;
import rw.terimbere.csams.testsupport.SchemeFixtures.Scheme;

/** Phase 5 security hardening: what an uploaded file really is, and how much of an unauthenticated callback is read. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadAndCallbackHardeningIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProviderCallbackProperties callbackProperties;

    private Scheme scheme;

    @BeforeEach
    void setUp() throws Exception {
        scheme = new SchemeFixtures(mockMvc, objectMapper, jdbc).scheme("ACCUMULATING");
    }

    @AfterEach
    void tearDown() {
        callbackProperties.setSecrets(Map.of());
    }

    @Test
    void anUploadWhoseContentIsNotWhatItClaimsIsRefusedAndAGenuineOneIsAccepted() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2};
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16};
        byte[] pdf = "%PDF-1.4 minimal".getBytes(StandardCharsets.UTF_8);
        byte[] script = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

        assertThat(upload("receipt.png", "image/png", png)).isEqualTo(200);
        assertThat(upload("receipt.jpg", "image/jpeg", jpeg)).isEqualTo(200);
        assertThat(upload("receipt.pdf", "application/pdf", pdf)).isEqualTo(200);

        // a script, or a different file type, behind a trusted-looking declared type
        assertThat(upload("receipt.png", "image/png", script)).isIn(400, 422);
        assertThat(upload("receipt.pdf", "application/pdf", png)).isIn(400, 422);
        assertThat(upload("receipt.png", "image/png", pdf)).isIn(400, 422);
        assertThat(upload("receipt.jpg", "image/jpeg", Arrays.copyOf(png, png.length))).isIn(400, 422);
        // blocked extensions and unlisted types stay refused
        assertThat(upload("run.sh", "image/png", png)).isIn(400, 422);
        assertThat(upload("page.html", "text/html", script)).isIn(400, 422);
        assertThat(jdbc.queryForObject("select count(*) from stored_files where cooperative_id = ?", Long.class, scheme.id()))
                .as("only the three genuine files were stored").isEqualTo(3);
    }

    @Test
    void anOversizedUnauthenticatedCallbackIsRejectedWithoutBeingProcessedAndNothingIsRecorded() throws Exception {
        callbackProperties.setSecrets(Map.of("MTN_MOMO", "hardening-secret"));
        byte[] huge = new byte[2 * 1024 * 1024];
        Arrays.fill(huge, (byte) 'a');
        MvcResult oversized = mockMvc.perform(post("/api/v1/public/payment-callbacks/MTN_MOMO")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", "deadbeef")
                        .content(huge))
                .andReturn();
        assertThat(oversized.getResponse().getStatus()).isEqualTo(401);
        // even a correctly signed body is refused when it is larger than the contract allows
        MvcResult bad = mockMvc.perform(post("/api/v1/public/payment-callbacks/MTN_MOMO")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}".getBytes(StandardCharsets.UTF_8)))
                .andReturn();
        assertThat(bad.getResponse().getStatus()).as("no signature at all").isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from trusted_transactions where cooperative_id = ?", Long.class, scheme.id())).isZero();
        // no credentials configured: the endpoint refuses everything and says why, without echoing anything it was sent
        callbackProperties.setSecrets(Map.of());
        MvcResult disabled = mockMvc.perform(post("/api/v1/public/payment-callbacks/MTN_MOMO")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", "anything")
                        .content("{\"secret\":\"leak-me\"}".getBytes(StandardCharsets.UTF_8)))
                .andReturn();
        assertThat(disabled.getResponse().getStatus()).isEqualTo(503);
        assertThat(disabled.getResponse().getContentAsString()).doesNotContain("leak-me");
    }

    private int upload(String name, String contentType, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart("/api/v1/cooperatives/" + scheme.id() + "/files")
                        .file(new MockMultipartFile("file", name, contentType, bytes))
                        .param("category", "CONTRIBUTION_EVIDENCE")
                        .header("Authorization", "Bearer " + scheme.memberA().token()))
                .andReturn()
                .getResponse()
                .getStatus();
    }
}
