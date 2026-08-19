package com.mailkube.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The settings surface, bound from {@code mailkube.*}.
 *
 * <p>Every field defaults to null, and that is the contract's "unset is omitted, not passed as
 * null" rule expressed in the only way Spring binding allows. The SDK has its own environment
 * fallbacks ({@code MAILKUBE_API_KEY} and friends) and its own validation; a
 * default invented here would silently win over the environment variable a consumer had already
 * set, and re-validating would duplicate what the SDK does better.
 *
 * <p>So this class holds no defaults, no {@code @NotNull}, and no fallback logic. It is a dumb
 * carrier, and {@code MailkubeAutoConfiguration} is the one place that decides
 * what an unset value means.
 *
 * <p>A mutable JavaBean rather than a constructor-bound record on purpose:
 * {@code @ConfigurationProperties} scanning, relaxed binding and the IDE metadata the
 * configuration processor generates all work with either, but a JavaBean lets an application
 * post-process the bean in an {@code EnvironmentPostProcessor}, which is a real pattern for
 * pulling an API key out of a secrets manager.
 */
@ConfigurationProperties(prefix = "mailkube")
public class MailkubeProperties {

    private String apiKey;

    private String baseUrl;

    private Duration timeout;

    private final Webhook webhook = new Webhook();

    /**
     * The API key.
     *
     * @return the configured key, or null to let the SDK read {@code MAILKUBE_API_KEY}
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Set the API key.
     *
     * @param apiKey the key
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * The API base URL.
     *
     * @return the configured base URL, or null to let the SDK decide
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Set the API base URL.
     *
     * @param baseUrl the base URL
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * The per-request timeout.
     *
     * @return the configured timeout, or null to let the SDK decide
     */
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * Set the per-request timeout.
     *
     * @param timeout the timeout
     */
    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * The webhook receiver's settings.
     *
     * @return the webhook settings, never null
     */
    public Webhook getWebhook() {
        return webhook;
    }

    /** The inbound webhook receiver's settings. */
    public static class Webhook {

        private boolean enabled;

        private String path = "/mailkube/webhook";

        private String secret;

        private Duration tolerance;

        /**
         * Whether the webhook endpoint is mapped.
         *
         * <p>Defaults to {@code false}, and that default is deliberate: an HTTP endpoint that
         * appears merely because a dependency landed on the classpath is a security surprise. A
         * consumer opts in.
         *
         * @return true when the endpoint should be registered
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Set whether the webhook endpoint is mapped.
         *
         * @param enabled true to register the endpoint
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * The path the endpoint is mapped to.
         *
         * @return the path
         */
        public String getPath() {
            return path;
        }

        /**
         * Set the path the endpoint is mapped to.
         *
         * @param path the path
         */
        public void setPath(String path) {
            this.path = path;
        }

        /**
         * The signing secret used to verify inbound deliveries.
         *
         * @return the secret, or null when none is configured
         */
        public String getSecret() {
            return secret;
        }

        /**
         * Set the signing secret.
         *
         * @param secret the secret
         */
        public void setSecret(String secret) {
            this.secret = secret;
        }

        /**
         * How much clock skew to tolerate on a delivery's timestamp.
         *
         * @return the tolerance, or null to use the SDK's default
         */
        public Duration getTolerance() {
            return tolerance;
        }

        /**
         * Set the timestamp tolerance.
         *
         * @param tolerance the tolerance
         */
        public void setTolerance(Duration tolerance) {
            this.tolerance = tolerance;
        }
    }
}
