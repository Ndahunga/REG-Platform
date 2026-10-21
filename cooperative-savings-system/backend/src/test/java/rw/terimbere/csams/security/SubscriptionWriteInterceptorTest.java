package rw.terimbere.csams.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import rw.terimbere.csams.modules.subscription.service.SubscriptionEntitlementService;
import rw.terimbere.csams.shared.exceptions.ValidationException;

class SubscriptionWriteInterceptorTest {

    private static final UUID COOP = UUID.fromString("a1b2c3d4-e5f6-4789-abcd-0123456789ab");
    private static final String BASE = "/api/v1/cooperatives/";

    private final SubscriptionEntitlementService entitlement = mock(SubscriptionEntitlementService.class);
    private final SubscriptionWriteInterceptor interceptor = new SubscriptionWriteInterceptor(entitlement);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void canonicalWriteToUnsubscribedCooperative_isStillBlocked() {
        authenticateMemberOf(COOP);
        doThrow(new IllegalStateException("subscription inactive")).when(entitlement).requireWriteAllowed(COOP);

        assertThatThrownBy(() -> preHandle("PUT", BASE + COOP + "/contributions/period"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("subscription inactive");
    }

    @Test
    void encodedOrMalformedCooperativeSegment_failsClosedForEveryMutatingMethod() {
        authenticateMemberOf(COOP);
        String id = COOP.toString();
        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            for (String segment : new String[] {
                "%61" + id.substring(1), id.replace("-", "%2d"), id.replace("b", "%62"), "not-a-uuid", id.substring(1)
            }) {
                assertThatThrownBy(() -> preHandle(method, BASE + segment + "/contributions/period"))
                        .as(method + " " + segment)
                        .isInstanceOf(ValidationException.class)
                        .hasMessage(CooperativeScopeFilter.INVALID_PATH_MESSAGE);
            }
        }
        verifyNoInteractions(entitlement);
    }

    @Test
    void invalidSegment_failsClosedEvenWithoutAnAuthenticatedPrincipal() {
        assertThatThrownBy(() -> preHandle("POST", BASE + "%31" + COOP.toString().substring(1) + "/fines"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void collectionMineUnrelatedAndReadRequests_areNotAffected() throws Exception {
        authenticateMemberOf(COOP);

        assertThat(preHandle("POST", "/api/v1/cooperatives")).isTrue();
        assertThat(preHandle("POST", BASE + "mine")).isTrue();
        assertThat(preHandle("POST", "/api/v1/notifications/read-all")).isTrue();
        assertThat(preHandle("GET", BASE + COOP + "/contributions")).isTrue();
        assertThat(preHandle("POST", BASE + COOP + "/billing/checkout")).isTrue();
        verify(entitlement, never()).requireWriteAllowed(COOP);
    }

    @Test
    void allowedWrite_consultsEntitlementForTheCanonicalCooperative() throws Exception {
        authenticateMemberOf(COOP);

        assertThat(preHandle("PATCH", BASE + COOP.toString().toUpperCase() + "/fines/" + UUID.randomUUID()))
                .isTrue();
        verify(entitlement).requireWriteAllowed(COOP);
    }

    private boolean preHandle(String method, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    private static void authenticateMemberOf(UUID cooperativeId) {
        UserPrincipal principal = UserPrincipal.builder()
                .id(UUID.randomUUID())
                .username("m")
                .password("")
                .roles(Set.of("MEMBER"))
                .permissions(Set.of())
                .cooperativeIds(Set.of(cooperativeId))
                .accountNonLocked(true)
                .enabled(true)
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    void exemptsAuthOnboardingPublicAndSuperAdminAdminPaths() {
        assertThat(SubscriptionWriteInterceptor.isExempt("POST", "/api/v1/auth/login")).isTrue();
        assertThat(SubscriptionWriteInterceptor.isExempt("PATCH", "/api/v1/auth/me")).isTrue();
        assertThat(SubscriptionWriteInterceptor.isExempt("POST", "/api/v1/onboarding/signup")).isTrue();
        assertThat(SubscriptionWriteInterceptor.isExempt("POST", "/api/v1/cooperatives")).isTrue();
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "PATCH", "/api/v1/cooperatives/11111111-1111-1111-1111-111111111111/status"))
                .isTrue();
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "POST", "/api/v1/cooperatives/11111111-1111-1111-1111-111111111111/administrators"))
                .isTrue();
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "GET", "/api/v1/cooperatives/11111111-1111-1111-1111-111111111111/subscription"))
                .isTrue();
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "GET", "/api/v1/cooperatives/11111111-1111-1111-1111-111111111111/billing/plans"))
                .isTrue();
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "GET", "/api/v1/cooperatives/11111111-1111-1111-1111-111111111111/billing/payments"))
                .isTrue();
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "POST", "/api/v1/cooperatives/11111111-1111-1111-1111-111111111111/billing/checkout"))
                .isTrue();
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "GET",
                        "/api/v1/cooperatives/11111111-1111-1111-1111-111111111111/billing/payments"
                                + "/22222222-2222-2222-2222-222222222222"))
                .isTrue();
        assertThat(SubscriptionWriteInterceptor.isExempt("POST", "/api/v1/public/billing/flutterwave/webhook"))
                .isTrue();
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "POST",
                        "/api/v1/public/billing/mtn/callback/22222222-2222-2222-2222-222222222222"))
                .isTrue();
        // Member payments (/payments) are ordinary financial writes: an expired scheme must be read-only for them.
        String payments = "/api/v1/cooperatives/11111111-1111-1111-1111-111111111111/payments";
        String paymentId = payments + "/22222222-2222-2222-2222-222222222222";
        assertThat(SubscriptionWriteInterceptor.isExempt("POST", payments)).isFalse();
        assertThat(SubscriptionWriteInterceptor.isExempt("POST", paymentId + "/start-review")).isFalse();
        assertThat(SubscriptionWriteInterceptor.isExempt("POST", paymentId + "/verify")).isFalse();
        assertThat(SubscriptionWriteInterceptor.isExempt("POST", paymentId + "/reject")).isFalse();
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "PUT", paymentId + "/allocations/33333333-3333-3333-3333-333333333333/loan-breakdown"))
                .isFalse();
        // Subscription billing payments keep their (intended) exemption.
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "POST", "/api/v1/cooperatives/11111111-1111-1111-1111-111111111111/billing/checkout"))
                .isTrue();
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "POST", "/api/v1/cooperatives/11111111-1111-1111-1111-111111111111/reports/export"))
                .isTrue();
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "POST",
                        "/api/v1/cooperatives/11111111-1111-1111-1111-111111111111/loans/repayment-preview"))
                .isTrue();
    }

    @Test
    void doesNotExemptOperationalWrites() {
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "POST", "/api/v1/cooperatives/11111111-1111-1111-1111-111111111111/members"))
                .isFalse();
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "PUT", "/api/v1/cooperatives/11111111-1111-1111-1111-111111111111/contributions/period"))
                .isFalse();
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "POST", "/api/v1/cooperatives/11111111-1111-1111-1111-111111111111/loans"))
                .isFalse();
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "PUT", "/api/v1/cooperatives/11111111-1111-1111-1111-111111111111"))
                .isFalse();
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "POST", "/api/v1/cooperatives/11111111-1111-1111-1111-111111111111/logo"))
                .isFalse();
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "POST",
                        "/api/v1/cooperatives/11111111-1111-1111-1111-111111111111/reports/share-whatsapp"))
                .isFalse();
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "POST",
                        "/api/v1/cooperatives/11111111-1111-1111-1111-111111111111/loans"
                                + "/22222222-2222-2222-2222-222222222222/schedule/share-whatsapp"))
                .isFalse();
        assertThat(SubscriptionWriteInterceptor.isExempt(
                        "POST", "/api/v1/cooperatives/11111111-1111-1111-1111-111111111111/reports"))
                .isFalse();
    }
}
