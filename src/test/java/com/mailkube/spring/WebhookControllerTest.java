package com.mailkube.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mailkube.Webhooks;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The webhook entry point, driven through MockMvc standalone.
 *
 * <p>Standalone rather than a full context: what is under test is the handler's own behaviour, and
 * the conditions that decide whether it is registered at all are {@code AutoConfigurationTest}'s
 * job. No server socket is bound.
 *
 * <p>Fixtures are signed with the SDK's own {@link Webhooks#sign} rather than a hand-rolled HMAC.
 * That is deliberate and it is the pressure the SDK contract's "ship the signing half too" clause
 * exists to create: a signature computed here from the prose would agree with its author's reading
 * of the docs rather than with the SDK, and the two would drift silently.
 */
class WebhookControllerTest {

    private static final String SECRET = "whsec_test";

    private final List<ApplicationEvent> published = new ArrayList<>();
    private MailkubeProperties.Webhook settings;

    @BeforeEach
    void reset() {
        published.clear();
        settings = new MailkubeProperties.Webhook();
        settings.setSecret(SECRET);
    }

    private MockMvc mockMvc() {
        ApplicationEventPublisher publisher = event -> published.add((ApplicationEvent) event);
        return MockMvcBuilders.standaloneSetup(new MailkubeWebhookController(settings, publisher))
                .build();
    }

    private static byte[] body() {
        return "{\"type\":\"email.delivered\",\"created_at\":\"2026-01-01T00:00:00Z\",\"data\":{}}"
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void acceptsAndPublishesAVerifiedDelivery() throws Exception {
        byte[] payload = body();
        String id = "evt_1";
        String timestamp = Instant.now().toString();

        mockMvc()
                .perform(post("/mailkube/webhook")
                        .content(payload)
                        .header("X-Webhook-Id", id)
                        .header("X-Webhook-Ts", timestamp)
                        .header("X-Webhook-Sig", Webhooks.sign(id, timestamp, payload, SECRET)))
                .andExpect(status().isNoContent());

        assertThat(published).hasSize(1);
        assertThat(((MailkubeWebhookEvent) published.getFirst()).event().type()).isEqualTo("email.delivered");
    }

    @Test
    void listensOnTheConfiguredPathRatherThanOnlyTheDefault() throws Exception {
        // `mailkube.webhook.path` reaches the handler through a placeholder in the @PostMapping
        // annotation, which is the ONE consumer of that property. Nothing else in this suite would
        // notice if the key were misspelled there: every other test posts to the default path, and
        // a placeholder whose key resolves to nothing quietly falls back to that same default. The
        // endpoint would then ignore the property in every consumer's application while every test
        // stayed green.
        byte[] payload = body();
        String id = "evt_path";
        String timestamp = Instant.now().toString();

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                        new MailkubeWebhookController(settings, event -> published.add((ApplicationEvent) event)))
                .addPlaceholderValue("mailkube.webhook.path", "/hooks/mailkube")
                .build();

        mockMvc.perform(post("/hooks/mailkube")
                        .content(payload)
                        .header("X-Webhook-Id", id)
                        .header("X-Webhook-Ts", timestamp)
                        .header("X-Webhook-Sig", Webhooks.sign(id, timestamp, payload, SECRET)))
                .andExpect(status().isNoContent());

        assertThat(published).hasSize(1);
    }

    @Test
    void rejectsAForgedSignature() throws Exception {
        byte[] payload = body();
        String timestamp = Instant.now().toString();

        mockMvc()
                .perform(post("/mailkube/webhook")
                        .content(payload)
                        .header("X-Webhook-Id", "evt_1")
                        .header("X-Webhook-Ts", timestamp)
                        .header("X-Webhook-Sig", "sha256=deadbeef"))
                .andExpect(status().isBadRequest());

        assertThat(published).isEmpty();
    }

    @Test
    void rejectsADeliveryWhoseBodyWasChangedAfterSigning() throws Exception {
        // The raw-bytes rule, stated as a test. The signature is computed over the original body;
        // if anything between the socket and the verifier re-serialized it, this is what fails.
        byte[] signed = body();
        String id = "evt_1";
        String timestamp = Instant.now().toString();
        String signature = Webhooks.sign(id, timestamp, signed, SECRET);
        byte[] tampered = "{\"type\":\"email.bounced\",\"created_at\":\"2026-01-01T00:00:00Z\",\"data\":{}}"
                .getBytes(StandardCharsets.UTF_8);

        mockMvc()
                .perform(post("/mailkube/webhook")
                        .content(tampered)
                        .header("X-Webhook-Id", id)
                        .header("X-Webhook-Ts", timestamp)
                        .header("X-Webhook-Sig", signature))
                .andExpect(status().isBadRequest());

        assertThat(published).isEmpty();
    }

    @Test
    void rejectsAStaleDelivery() throws Exception {
        byte[] payload = body();
        String id = "evt_1";
        String timestamp = Instant.now().minusSeconds(4000).toString();

        mockMvc()
                .perform(post("/mailkube/webhook")
                        .content(payload)
                        .header("X-Webhook-Id", id)
                        .header("X-Webhook-Ts", timestamp)
                        .header("X-Webhook-Sig", Webhooks.sign(id, timestamp, payload, SECRET)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void honoursAConfiguredTolerance() throws Exception {
        settings.setTolerance(java.time.Duration.ofHours(2));
        byte[] payload = body();
        String id = "evt_1";
        String timestamp = Instant.now().minusSeconds(4000).toString();

        mockMvc()
                .perform(post("/mailkube/webhook")
                        .content(payload)
                        .header("X-Webhook-Id", id)
                        .header("X-Webhook-Ts", timestamp)
                        .header("X-Webhook-Sig", Webhooks.sign(id, timestamp, payload, SECRET)))
                .andExpect(status().isNoContent());
    }

    @Test
    void answersFiveHundredWhenNoSecretIsConfigured() throws Exception {
        // Not 400: the delivery is fine and this application is misconfigured. A 400 would tell the
        // sender to stop retrying something that would succeed the moment the secret is set.
        settings.setSecret(null);

        mockMvc().perform(post("/mailkube/webhook").content(body())).andExpect(status().isInternalServerError());

        assertThat(published).isEmpty();
    }

    @Test
    void treatsABlankSecretAsNoSecret() {
        // A blank value is what an unset environment variable interpolates to in a properties file,
        // so it has to mean "not configured" rather than "the secret is the empty string".
        settings.setSecret("   ");

        org.assertj.core.api.Assertions.assertThatCode(() -> mockMvc()
                        .perform(post("/mailkube/webhook").content(body()))
                        .andExpect(status().isInternalServerError()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsADeliveryWithNoSignatureHeadersAtAll() throws Exception {
        mockMvc().perform(post("/mailkube/webhook").content(body())).andExpect(status().isBadRequest());
    }
}
