package com.mailkube.spring;

import com.mailkube.Webhooks;
import com.mailkube.exception.MailkubeException;
import com.mailkube.model.WebhookEvent;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The inbound webhook receiver.
 *
 * <p>Registered only when {@code mailkube.webhook.enabled} is true and
 * {@code spring-web} is present. See {@code MailkubeAutoConfiguration.WebhookConfiguration}.
 *
 * <p><b>The body is taken as {@code byte[]}</b>, and that is the contract's raw-bytes rule realized
 * in Spring. Declaring a DTO parameter hands the stream to Jackson, which parses and would have to
 * re-serialize to verify — and re-serializing changes bytes, so the signature never matches. A
 * {@code String} parameter is no better: it applies a charset decision the signature was not
 * computed over.
 *
 * <p>This class contains no cryptography. It adapts the request to
 * {@link Webhooks#verify(byte[], Map, String, Duration)} and publishes the result.
 *
 * <p><b>CSRF is documented, not exempted.</b> With Spring Security on the classpath its filter
 * rejects this POST before the handler runs, and a library must never mutate an application's
 * {@code SecurityFilterChain} — doing so would silently widen the security posture of every
 * application that added a mail dependency. The README carries the one-line ignore a consumer adds.
 */
@RestController
public class MailkubeWebhookController {

    private final MailkubeProperties.Webhook settings;
    private final ApplicationEventPublisher publisher;

    /**
     * Build the receiver.
     *
     * @param settings the webhook settings
     * @param publisher the application's event publisher
     */
    public MailkubeWebhookController(MailkubeProperties.Webhook settings, ApplicationEventPublisher publisher) {
        this.settings = settings;
        this.publisher = publisher;
    }

    /**
     * Receive, verify and publish one delivery.
     *
     * <p>The path is resolved from {@code mailkube.webhook.path} through a property
     * placeholder, because a mapping annotation takes compile-time constants only and the path has to
     * stay configurable.
     *
     * <p>Headers arrive as a map rather than being named one by one: which headers carry the
     * signature is the SDK's business, and listing them here would put a copy of the signature scheme
     * in this repository, to go stale the first time the SDK adds one.
     *
     * @param payload the raw request body
     * @param headers every request header, lowercased
     * @return 204 on success, 400 on a rejected delivery, 500 when no secret is configured
     */
    @PostMapping(path = "${mailkube.webhook.path:/mailkube/webhook}")
    public ResponseEntity<Void> receive(@RequestBody byte[] payload, @RequestHeader Map<String, String> headers) {
        String secret = settings.getSecret();
        if (secret == null || secret.isBlank()) {
            // Not 400: the delivery is fine, this application is misconfigured. Answering 400 would
            // tell the sender to stop retrying a delivery that would succeed the moment the secret
            // is set, and 5xx is what makes the sender retry after the fix.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        try {
            WebhookEvent event = verify(payload, headers, secret);
            // Synchronous by default, which is the framework's own default and therefore the
            // flavour this integration inherits. An application wanting otherwise annotates its
            // listener with @Async, which is its decision rather than one taken for it here.
            publisher.publishEvent(new MailkubeWebhookEvent(this, event));
            return ResponseEntity.noContent().build();
        } catch (MailkubeException e) {
            // Every verification failure — bad signature, stale timestamp, unparseable body — is a
            // 400. Distinguishing them in the response would tell an attacker which half of the
            // check failed.
            return ResponseEntity.badRequest().build();
        }
    }

    private WebhookEvent verify(byte[] payload, Map<String, String> headers, String secret) {
        Map<String, String> normalized = lowercaseKeys(headers);
        Duration tolerance = settings.getTolerance();
        return tolerance == null
                ? Webhooks.verify(payload, normalized, secret)
                : Webhooks.verify(payload, normalized, secret, tolerance);
    }

    /**
     * Lowercase every header name.
     *
     * <p>{@code @RequestHeader Map} preserves whatever casing the container reported, and HTTP header
     * names are case-insensitive, so the SDK's lookup would miss {@code X-Webhook-Id} while matching
     * {@code x-webhook-id}. Normalizing here rather than asking the SDK to is the adapter's job.
     */
    private static Map<String, String> lowercaseKeys(Map<String, String> headers) {
        Map<String, String> normalized = new HashMap<>(headers.size());
        headers.forEach((name, value) -> normalized.put(name.toLowerCase(Locale.ROOT), value));
        return normalized;
    }
}
