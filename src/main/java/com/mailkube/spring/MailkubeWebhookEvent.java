package com.mailkube.spring;

import com.mailkube.model.WebhookEvent;
import java.io.Serial;
import org.springframework.context.ApplicationEvent;

/**
 * Published when a verified webhook delivery arrives.
 *
 * <p><b>One Spring event carrying the SDK's typed event</b>, not one Spring event class per webhook
 * type. The SDK already owns that catalogue; mirroring it here would mean a class per event type to
 * add, in lockstep, forever, and a listener would still have to switch on the SDK type to do
 * anything useful. A listener that wants one kind checks {@code event().type()}.
 *
 * <pre>{@code
 * @Component
 * class DeliveryListener {
 *     @EventListener
 *     void on(MailkubeWebhookEvent event) {
 *         log.info("{}", event.event().type());
 *     }
 * }
 * }</pre>
 */
public class MailkubeWebhookEvent extends ApplicationEvent {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient WebhookEvent event;

    /**
     * Wrap a verified delivery.
     *
     * @param source the component that received it
     * @param event the verified event
     */
    public MailkubeWebhookEvent(Object source, WebhookEvent event) {
        super(source);
        this.event = event;
    }

    /**
     * The verified event.
     *
     * @return the SDK's event
     */
    public WebhookEvent event() {
        return event;
    }
}
