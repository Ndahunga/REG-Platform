package rw.terimbere.csams.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import rw.terimbere.csams.security.CooperativePath.Kind;

class CooperativePathTest {

    private static final UUID COOP = UUID.fromString("a1b2c3d4-e5f6-4789-abcd-0123456789ab");
    private static final String BASE = "/api/v1/cooperatives/";

    @Test
    void canonicalUuid_isCooperativeScoped_atAnyDepth() {
        for (String path : new String[] {
            BASE + COOP,
            BASE + COOP + "/",
            BASE + COOP + "/contributions",
            BASE + COOP + "/contributions/period",
            BASE + COOP + "/members/" + UUID.randomUUID(),
            BASE + COOP + "/members/" + UUID.randomUUID() + "/status",
        }) {
            var result = CooperativePath.parse(path);
            assertThat(result.kind()).as(path).isEqualTo(Kind.COOPERATIVE);
            assertThat(result.cooperativeId()).as(path).isEqualTo(COOP);
        }
    }

    @Test
    void uppercaseUuid_isCooperativeScoped() {
        var result = CooperativePath.parse(BASE + COOP.toString().toUpperCase() + "/loans");
        assertThat(result.kind()).isEqualTo(Kind.COOPERATIVE);
        assertThat(result.cooperativeId()).isEqualTo(COOP);
    }

    @Test
    void collectionAndMineLiterals_areNotUuidScoped_andNotRejected() {
        for (String path : new String[] {"/api/v1/cooperatives", BASE, BASE + "mine", BASE + "mine/"}) {
            assertThat(CooperativePath.parse(path).kind()).as(path).isEqualTo(Kind.COOPERATIVE_COLLECTION);
            assertThat(CooperativePath.parse(path).cooperativeId()).as(path).isNull();
        }
    }

    @Test
    void unrelatedPaths_areNotCooperativeScoped() {
        for (String path : new String[] {
            "/api/v1/auth/login",
            "/api/v1/notifications",
            "/api/v1/notifications/pending-approvals",
            "/api/v1/files/cooperatives/" + COOP + "/x.pdf",
            "/api/v1/platform/dashboard/overview",
            "/api/v1/cooperativesXYZ",
            "/api/v1/cooperatives-archive/" + COOP,
            "/other/api/v1/cooperatives/" + COOP,
            "/",
            "",
        }) {
            assertThat(CooperativePath.parse(path).kind()).as(path).isEqualTo(Kind.NOT_COOPERATIVE_SCOPED);
        }
        assertThat(CooperativePath.parse((String) null).kind()).isEqualTo(Kind.NOT_COOPERATIVE_SCOPED);
    }

    @Test
    void percentEncodedUuidCharacters_areInvalid() {
        String id = COOP.toString();
        String[] encoded = {
            "%61" + id.substring(1), // first (hex letter) character encoded
            id.replace("-", "%2d"), // hyphens encoded
            id.replace("-", "%2D"),
            id.substring(0, 1).replace("a", "%61") + id.substring(1, 8) + "%2d" + id.substring(9),
            id.replace("b", "%62"), // a hex letter in the middle encoded
            "%31" + UUID.randomUUID().toString().substring(1), // encoded digit
        };
        for (String segment : encoded) {
            assertThat(CooperativePath.parse(BASE + segment + "/contributions/period").kind())
                    .as(segment)
                    .isEqualTo(Kind.INVALID);
            assertThat(CooperativePath.parse(BASE + segment).kind()).as(segment).isEqualTo(Kind.INVALID);
        }
    }

    @Test
    void malformedOrUnexpectedSegments_areInvalid() {
        for (String segment : new String[] {
            "not-a-uuid",
            "12345",
            COOP.toString().substring(1), // too short
            COOP + "0", // too long
            COOP.toString().replace('a', 'g'), // non-hex character
            COOP + "%2e", // trailing encoded dot
            COOP + ".", // trailing dot
            "mine-extra",
            "MINE",
            "..",
            ".",
            "%2e%2e",
            "null",
        }) {
            assertThat(CooperativePath.parse(BASE + segment + "/loans").kind())
                    .as(segment)
                    .isEqualTo(Kind.INVALID);
        }
        assertThat(CooperativePath.parse(BASE + "mine/extra").kind()).isEqualTo(Kind.INVALID);
        assertThat(CooperativePath.parse("/api/v1/cooperatives//" + COOP).kind()).isEqualTo(Kind.INVALID);
    }

    @Test
    void queryAndFragmentAreIgnored() {
        var result = CooperativePath.parse(BASE + COOP + "/contributions/period?year=2026&month=3");
        assertThat(result.kind()).isEqualTo(Kind.COOPERATIVE);
        assertThat(result.cooperativeId()).isEqualTo(COOP);
        assertThat(CooperativePath.parse(BASE + "mine?x=1").kind()).isEqualTo(Kind.COOPERATIVE_COLLECTION);
        assertThat(CooperativePath.parse(BASE + "%31?" + COOP).kind()).isEqualTo(Kind.INVALID);
    }

    @Test
    void requestOverload_honoursContextPathAndRawUri() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/csams" + BASE + COOP + "/fines");
        request.setRequestURI("/csams" + BASE + COOP + "/fines");
        request.setContextPath("/csams");
        assertThat(CooperativePath.parse(request).cooperativeId()).isEqualTo(COOP);

        MockHttpServletRequest encoded = new MockHttpServletRequest("PUT", BASE + "%31" + COOP.toString().substring(1));
        encoded.setRequestURI(BASE + "%31" + COOP.toString().substring(1));
        assertThat(CooperativePath.parse(encoded).kind()).isEqualTo(Kind.INVALID);
    }
}
