package rw.terimbere.csams.modules.subscription.config;

import java.math.BigDecimal;
import java.util.Locale;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Platform subscription prices for OuWealth. Kept in configuration (not a Plan table)
 * because MVP has a single global catalog, no per-cooperative price overrides, and
 * the project already centralizes similar knobs via {@code @ConfigurationProperties}.
 *
 * <p>Payment provider settings under {@code payment} are the single source of truth for
 * MTN MoMo and Flutterwave (bound from {@code application.yml} / environment variables).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.subscription")
public class SubscriptionProperties {

    private String currency = "RWF";
    private String timezone = "Africa/Kigali";
    private int trialMonths = 4;
    private int trialWarningDays = 14;
    private BigDecimal monthlyCharge = new BigDecimal("2000.0000");
    private BigDecimal annualListPrice = new BigDecimal("24000.0000");
    private BigDecimal annualCharge = new BigDecimal("18000.0000");
    private BigDecimal annualDiscountPercent = new BigDecimal("25");
    private Payment payment = new Payment();

    @Getter
    @Setter
    public static class Payment {
        /**
         * Reuse a PENDING checkout for the same cooperative, plan, channel, and payer
         * identity only inside this window. Default 15 minutes covers double-clicks and
         * in-flight MoMo/hosted-checkout approval without blocking a later legitimate retry.
         *
         * <p>This is <strong>checkout retry eligibility</strong>, not provider final status.
         * Expiry of this window does not mark a payment FAILED.
         */
        private int pendingReuseMinutes = 15;

        /**
         * PENDING payments older than this are excluded from scheduled reconciliation to
         * avoid indefinite polling. They remain PENDING until a callback/webhook, a user
         * status check, or a new checkout cancels/replaces them. Default 48 hours.
         * Provider truth still wins if a late SUCCESS arrives via callback/webhook.
         */
        private int pendingAbandonHours = 48;

        private Reconciliation reconciliation = new Reconciliation();
        private Mtn mtn = new Mtn();
        private Flutterwave flutterwave = new Flutterwave();

        @Getter
        @Setter
        public static class Reconciliation {
            /** When true, a scheduled job re-verifies eligible PENDING payments. */
            private boolean enabled = false;
            /** Delay between reconciliation sweeps (default 5 minutes). */
            private long fixedDelayMs = 300_000L;
            /** Initial delay after startup before the first sweep. */
            private long initialDelayMs = 60_000L;
            /**
             * Skip payments newer than this age so callbacks/webhooks can win the race
             * without immediate double-verify noise (default 2 minutes).
             */
            private int minAgeMinutes = 2;
            /** Max payments processed per sweep. */
            private int batchSize = 50;
        }

        @Getter
        @Setter
        public static class Mtn {
            /**
             * Master switch. Local development stays up when this is false even if
             * credentials are blank. Production with enabled=true must supply secrets.
             */
            private boolean enabled = false;

            private String baseUrl = "https://sandbox.momodeveloper.mtn.com";
            private String subscriptionKey = "";
            private String apiUser = "";
            private String apiKey = "";
            /**
             * Explicit MTN Collection target environment: {@code sandbox} or a production
             * value such as {@code mtnrwanda}. Never inferred from {@link #baseUrl}.
             */
            private String targetEnvironment = "sandbox";
            private String callbackUrl = "";
            private int connectTimeoutMs = 5_000;
            private int readTimeoutMs = 20_000;

            public boolean isConfigured() {
                return enabled
                        && StringUtils.hasText(baseUrl)
                        && StringUtils.hasText(subscriptionKey)
                        && StringUtils.hasText(apiUser)
                        && StringUtils.hasText(apiKey)
                        && StringUtils.hasText(targetEnvironment);
            }

            public boolean isSandboxEnvironment() {
                return "sandbox".equalsIgnoreCase(trimOrEmpty(targetEnvironment));
            }
        }

        @Getter
        @Setter
        public static class Flutterwave {
            private boolean enabled = false;
            private String baseUrl = "https://api.flutterwave.com/v3";
            /**
             * Explicit Flutterwave mode: {@code test} or {@code live}. Never inferred from
             * secret-key prefixes or URL path checks in payment clients.
             */
            private String mode = "test";
            private String publicKey = "";
            private String secretKey = "";
            /** Dashboard secret hash compared to the {@code verif-hash} webhook header. */
            private String secretHash = "";
            private String redirectUrl = "";
            private String webhookUrl = "";
            private int connectTimeoutMs = 5_000;
            private int readTimeoutMs = 20_000;

            public boolean isConfigured() {
                return enabled
                        && StringUtils.hasText(baseUrl)
                        && StringUtils.hasText(secretKey)
                        && StringUtils.hasText(redirectUrl)
                        && StringUtils.hasText(mode);
            }

            public boolean isWebhookConfigured() {
                return isConfigured() && StringUtils.hasText(secretHash);
            }

            public boolean isTestMode() {
                return "test".equalsIgnoreCase(trimOrEmpty(mode));
            }

            public boolean isLiveMode() {
                return "live".equalsIgnoreCase(trimOrEmpty(mode));
            }
        }
    }

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
