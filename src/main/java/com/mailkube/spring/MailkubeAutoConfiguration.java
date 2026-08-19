package com.mailkube.spring;

import com.mailkube.MailkubeClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.mail.MailSender;

/**
 * The one config module: settings in, a configured client and sender out.
 *
 * <p>Ordered ahead of Boot's own mail auto-configuration. That is not tidiness: Boot's
 * {@code MailSenderAutoConfiguration} is {@code @ConditionalOnMissingBean(MailSender.class)}, so
 * whichever runs first wins, and without an explicit order the winner depends on classpath order.
 *
 * <p><b>The ordering is declared by name, not by class</b>, and that is load-bearing. Boot 4 moved
 * {@code MailSenderAutoConfiguration} from {@code org.springframework.boot.autoconfigure.mail} to
 * {@code org.springframework.boot.mail.autoconfigure}, in a new {@code spring-boot-mail} jar. A
 * {@code before = MailSenderAutoConfiguration.class} reference names one package and cannot compile
 * against both lines, and on a Boot 4 application without the mail starter the class is not present
 * at all. {@code beforeName} takes strings, tolerates an absent class, and lets one artifact support
 * every version in the matrix — so both names are listed. Verified: {@code beforeName()} exists on
 * {@code @AutoConfiguration} in Boot 3.4, 3.5 and 4.0 alike.
 */
@AutoConfiguration(
        beforeName = {
            "org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration",
            "org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration"
        })
@ConditionalOnClass(MailkubeClient.class)
@EnableConfigurationProperties(MailkubeProperties.class)
public class MailkubeAutoConfiguration {

    /**
     * Build the SDK client from the bound settings.
     *
     * <p><b>{@code @Lazy}, and that is load-bearing rather than an optimization.</b> The SDK
     * validates its configuration in the constructor and throws when no API key is resolvable from
     * either the builder or the environment. An eager bean would turn that into a
     * {@code BeanCreationException}, so merely having this starter on the classpath would stop an
     * application from starting at all — including one that never sends mail, and including
     * {@code --help} and a test slice. Deferring construction to first use means a misconfigured
     * application fails on the send, where the error names what is actually wrong.
     *
     * <p>The annotation alone does not achieve that: see {@link #mailkubeMailSender}, whose
     * injection point is what would otherwise force this bean to be built at startup anyway.
     *
     * <p>The sibling Laravel integration resolves its client at the same point and for the same
     * reason, so the two behave alike.
     *
     * <p>The client is {@code AutoCloseable} and Spring infers {@code close()} as the destroy method,
     * so the HTTP client it owns is released when the context shuts down. Do not add an explicit
     * {@code destroyMethod}: it is already correct, and naming it again only invites someone to
     * "fix" it to something else. Laziness does not change that — a bean that was never built has
     * nothing to release, and one that was is still destroyed normally.
     *
     * <p>Every setter is conditional, which is how "an unset setting is omitted, not passed as null"
     * is expressed against a builder. Passing an explicit null would defeat the SDK's own
     * environment-variable fallbacks, so a consumer who sets only {@code MAILKUBE_API_KEY}
     * and no Spring property still gets a working client.
     *
     * @param properties the bound settings
     * @return the client
     */
    @Bean
    @Lazy
    @ConditionalOnMissingBean
    public MailkubeClient mailkubeClient(MailkubeProperties properties) {
        MailkubeClient.Builder builder = MailkubeClient.builder();
        if (properties.getApiKey() != null) {
            builder.apiKey(properties.getApiKey());
        }
        if (properties.getBaseUrl() != null) {
            builder.baseUrl(properties.getBaseUrl());
        }
        if (properties.getTimeout() != null) {
            builder.timeout(properties.getTimeout());
        }
        // Always set, and always this artifact's own name and version rather than a literal: it is
        // what makes traffic from this starter distinguishable from direct SDK use. The SDK's own
        // token stays leading, so the header reads `mailkube-java/x.y.z mailkube-spring-boot/a.b.c`.
        builder.userAgentSuffix("mailkube-spring-boot/" + Version.current());
        return builder.build();
    }

    /**
     * Expose the sender.
     *
     * <p>{@code @ConditionalOnMissingBean} on the interface, not on this class: an application that
     * defines any {@code MailSender} of its own — including Boot's JavaMail one — keeps it. A
     * starter that cannot be overridden is a starter that has to be removed.
     *
     * <p><b>The client is passed as a supplier, not injected.</b> Marking the client bean
     * {@code @Lazy} is not enough on its own, because this bean is eager and injecting a lazy bean
     * forces it to be built anyway. The usual fix — {@code @Lazy} on the parameter, so Spring
     * injects a proxy — <b>cannot work here</b>: {@code MailkubeClient} is
     * {@code final}, and the context fails to start with "Cannot subclass final class" because
     * CGLIB has nothing to subclass. Verified, not assumed.
     *
     * <p>{@code ObjectProvider::getObject} is the same deferral through Spring's own API, and it
     * keeps the client a real bean: still a singleton, still closed at shutdown, and still
     * overridable by an application that defines its own.
     *
     * @param client provides the SDK client on first use
     * @return the sender
     */
    @Bean
    @ConditionalOnMissingBean(MailSender.class)
    public MailkubeMailSender mailkubeMailSender(ObjectProvider<MailkubeClient> client) {
        return new MailkubeMailSender(client::getObject);
    }

    /**
     * The inbound webhook receiver, registered only when a web application opts in.
     *
     * <p>Nested rather than inline so {@code spring-web} is touched only when the outer conditions
     * already hold: the controller type is referenced in this class's signature, and on a batch
     * application with no {@code spring-web} that would be a {@code NoClassDefFoundError} at
     * configuration time rather than a cleanly skipped bean.
     *
     * <p>{@code @ConditionalOnProperty} with <b>no {@code matchIfMissing}</b>, so the endpoint is off
     * unless asked for. An HTTP endpoint that appears merely because a dependency was added is a
     * security surprise, and this one accepts unauthenticated POSTs by design.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    @ConditionalOnProperty(prefix = "mailkube.webhook", name = "enabled", havingValue = "true")
    public static class WebhookConfiguration {

        /**
         * Expose the webhook controller.
         *
         * @param properties the bound settings
         * @param publisher the application's event publisher
         * @return the controller
         */
        @Bean
        @ConditionalOnMissingBean
        public MailkubeWebhookController mailkubeWebhookController(
                MailkubeProperties properties, ApplicationEventPublisher publisher) {
            return new MailkubeWebhookController(properties.getWebhook(), publisher);
        }
    }
}
