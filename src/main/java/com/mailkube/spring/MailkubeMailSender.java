package com.mailkube.spring;

import com.mailkube.MailkubeClient;
import com.mailkube.exception.ApiException;
import com.mailkube.exception.AuthenticationException;
import com.mailkube.exception.ConfigurationException;
import com.mailkube.exception.MailkubeException;
import com.mailkube.model.Email;
import com.mailkube.model.SendEmailParams;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

/**
 * Sends mail through the API, as a Spring {@link MailSender}.
 *
 * <p>Two surfaces, on purpose, and the split is the point:
 *
 * <ul>
 *   <li>{@link #send(SimpleMailMessage...)} is the <b>drop-in compatibility path</b> for code already
 *       typed against {@code MailSender}. It carries exactly what {@code SimpleMailMessage} carries,
 *       which is plain text and addresses.
 *   <li>{@link #send(SendEmailParams)} is the <b>full-fidelity path</b>: HTML, attachments, headers,
 *       tags, templates, scheduling and idempotency, expressed in the SDK's own parameters with no
 *       intermediate type to lose them.
 * </ul>
 *
 * <p><b>This is deliberately not a {@code JavaMailSender}.</b> That interface requires
 * {@code createMimeMessage()} and {@code send(MimeMessage...)}, which would mean taking a
 * {@code jakarta.mail} dependency and then parsing MIME back into fields — the second wire format
 * the integration contract forbids, and it would make {@code spring.mail.host} and
 * {@code spring.mail.port} look meaningful when this starter ignores them entirely. See
 * {@code .rules/SPRING_BOOT_INTEGRATION.md}.
 *
 * <p>Synchronous, because {@code MailSender} is: the flavour is inherited from the framework's
 * extension point, not from the SDK. An application wanting asynchrony annotates its own caller with
 * {@code @Async}, which is its decision to make.
 */
public class MailkubeMailSender implements MailSender {

    private final Supplier<MailkubeClient> client;

    /**
     * Wrap an SDK client.
     *
     * @param client the client to send through
     */
    public MailkubeMailSender(MailkubeClient client) {
        this(() -> client);
    }

    /**
     * Wrap a client that is resolved on first use.
     *
     * <p>This is the constructor the auto-configuration calls, and the indirection is not a
     * flourish. The SDK validates its configuration in ITS constructor and throws when no API key is
     * resolvable, so building the client while the context starts turns a missing key into a failure
     * to start — for every application with this starter on the classpath, including one that never
     * sends mail.
     *
     * <p>A {@code @Lazy} injection point would be the idiomatic Spring answer and it does not work
     * here: {@code MailkubeClient} is {@code final}, so Spring cannot generate
     * the CGLIB subclass a lazy proxy needs, and the context fails with "Cannot subclass final
     * class". A supplier expresses the same deferral with no proxy at all.
     *
     * <p>Not memoized: the bean is a singleton, so Spring's own singleton cache already guarantees
     * the underlying client is built once. Caching again here would be a second answer to a question
     * that already has one.
     *
     * @param client supplies the client on first use
     */
    public MailkubeMailSender(Supplier<MailkubeClient> client) {
        this.client = client;
    }

    /**
     * Send one or more simple messages.
     *
     * <p>Each message is sent independently and in order. A failure part way through leaves the
     * earlier sends done, which is inherent to an API that has no batch transaction, so the
     * {@link MailSendException} names exactly which messages failed rather than implying the whole
     * call was atomic.
     *
     * @param messages the messages to send
     * @throws MailException if any message fails to send
     */
    @Override
    public void send(SimpleMailMessage... messages) {
        if (messages == null || messages.length == 0) {
            return;
        }
        // Keyed by the original message, which is the contract Spring code reads: callers index
        // this map by the message they handed in to find out which ones did not go.
        Map<Object, Exception> failures = new LinkedHashMap<>();
        for (SimpleMailMessage message : messages) {
            try {
                send(MailkubePayload.from(message));
            } catch (MailException e) {
                failures.put(message, e);
            }
        }
        if (!failures.isEmpty()) {
            throw new MailSendException(failures);
        }
    }

    /**
     * Send with the SDK's full parameter set.
     *
     * <p>The full-fidelity path: everything {@code SimpleMailMessage} cannot express reaches the API
     * through here, and nothing is re-encoded on the way.
     *
     * @param params the send parameters
     * @return the created email
     * @throws MailException if the send fails
     */
    public Email send(SendEmailParams params) {
        try {
            return client.get().emails().send(params);
        } catch (MailkubeException e) {
            throw translate(e);
        }
    }

    /**
     * The SDK client this sender uses.
     *
     * @return the client
     */
    public MailkubeClient client() {
        return client.get();
    }

    /**
     * Translate an SDK exception into Spring's mail hierarchy.
     *
     * <p>This is the boundary the contract requires, and the failure it prevents is invisible in
     * normal use: {@code spring-retry}, Spring Integration's mail adapters and every
     * {@code @ControllerAdvice} handler are written against {@link MailException}, so an SDK
     * exception escaping this method is caught by none of them and the mail simply does not send.
     *
     * <p>The taxonomy is deliberately shallow. Authentication and configuration get their own Spring
     * types because Spring has ones that mean exactly that; everything else is a
     * {@link MailSendException}. Mapping 429 and 5xx to different Spring types would be re-deciding
     * retryability here, and that decision belongs to the SDK — the contract's "deciding what an
     * HTTP 429 means does not" test.
     *
     * @param e the SDK exception
     * @return the equivalent Spring exception, with the cause preserved
     */
    private static MailException translate(MailkubeException e) {
        if (e instanceof AuthenticationException) {
            return new MailAuthenticationException(e);
        }
        if (e instanceof ConfigurationException) {
            // A misconfigured client cannot be fixed by retrying, which is exactly what
            // MailPreparationException signals to a caller that distinguishes them.
            return new MailPreparationException(e.getMessage(), e);
        }
        return new MailSendException(describe(e), e);
    }

    /**
     * Describe an SDK failure without re-encoding its taxonomy.
     *
     * <p>The request id is included when the SDK exposes one: it is the single most useful thing to
     * have in a log line when asking about a failed send, and it is otherwise lost the moment the
     * exception is wrapped.
     */
    private static String describe(MailkubeException e) {
        if (e instanceof ApiException api && api.requestId() != null) {
            return e.getMessage() + " (request id: " + api.requestId() + ")";
        }
        return e.getMessage();
    }

    /**
     * Send several parameter sets, preserving order.
     *
     * @param params the send parameters
     * @return the created emails, in the same order
     * @throws MailException if any send fails
     */
    public List<Email> send(List<SendEmailParams> params) {
        return params.stream().map(this::send).toList();
    }
}
