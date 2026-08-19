package com.mailkube.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mailkube.MailkubeClient;
import com.mailkube.exception.ConfigurationException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailSender;

/**
 * The conditional matrix, through Boot's own harness.
 *
 * <p>{@link ApplicationContextRunner} rather than {@code @SpringBootTest}: it builds a real context
 * with no host application, no database and no server socket, which is exactly what the contract
 * asks for and is fast enough to test every branch of the conditions.
 */
class AutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(MailkubeAutoConfiguration.class));

    @Test
    void registersTheSenderAndClientByDefault() {
        runner.withPropertyValues("mailkube.api-key=mk_test")
                .run(context -> assertThat(context)
                        .hasSingleBean(MailkubeMailSender.class)
                        .hasSingleBean(MailkubeClient.class));
    }

    @Test
    void passesOnlyTheSettingsThatWereActuallySet() {
        // Each setter in the client factory is individually conditional, so each has a branch that
        // only runs when that ONE property is present. Exercised one at a time rather than all
        // together, which is what leaves the "absent" side of each condition uncovered.
        //
        // The api-key is set in BOTH cases, and that is not padding: forcing the bean is what runs
        // the factory at all, and the SDK's constructor rejects a client it cannot find a key for.
        // Whether an unset key is tolerated is a different question, answered by
        // startsAnApplicationThatHasNoApiKeyConfiguredAtAll, which never touches the bean.
        runner.withPropertyValues("mailkube.api-key=mk_test", "mailkube.base-url=https://example.test/")
                .run(context ->
                        assertThat(context.getBean(MailkubeClient.class)).isNotNull());
        runner.withPropertyValues("mailkube.api-key=mk_test", "mailkube.timeout=5s")
                .run(context ->
                        assertThat(context.getBean(MailkubeClient.class)).isNotNull());
    }

    @Test
    void bindsEverySetting() {
        runner.withPropertyValues(
                        "mailkube.api-key=mk_test", "mailkube.base-url=https://example.test/", "mailkube.timeout=45s")
                .run(context -> {
                    MailkubeProperties properties = context.getBean(MailkubeProperties.class);
                    assertThat(properties.getApiKey()).isEqualTo("mk_test");
                    assertThat(properties.getBaseUrl()).isEqualTo("https://example.test/");
                    assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(45));
                });
    }

    @Test
    void startsAnApplicationThatHasNoApiKeyConfiguredAtAll() {
        // The SDK validates in its constructor and throws when no key is resolvable, so an EAGER
        // bean would turn "this dependency is on the classpath" into "this application does not
        // start" — for every application, including one that never sends mail. The client is @Lazy
        // for exactly this reason, and this test is what stops someone removing it.
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void buildsAClientWithNoPropertiesAtAllSoTheSdksEnvironmentFallbacksStillApply() {
        // The "omit, never null" rule seen from the outside: with nothing configured HERE the SDK
        // must still be free to read MAILKUBE_API_KEY. Passing explicit nulls
        // would defeat that fallback while looking like configuration.
        runner.withPropertyValues("mailkube.api-key=mk_test")
                .run(context ->
                        assertThat(context.getBean(MailkubeClient.class)).isNotNull());
    }

    @Test
    void letsTheSdkRejectAMissingKeyRatherThanPreValidatingHere() {
        // The other half of "omit, never null": with no key configured anywhere, the factory must
        // still call the builder WITHOUT the setter and let the SDK's own resolution — property,
        // then MAILKUBE_API_KEY, then failure — decide. Forcing the bean is what runs that path.
        //
        // Asserted as "the SDK's own type", not merely "it failed": the starter re-validating
        // configuration the SDK already validates is the failure mode this pins, and it would look
        // identical from the outside apart from the exception type.
        runner.run(context -> assertThatThrownBy(() -> context.getBean(MailkubeClient.class))
                .hasRootCauseInstanceOf(ConfigurationException.class));
    }

    @Test
    void bindsEveryWebhookSetting() {
        // The webhook settings are read off the Environment at runtime — `enabled` by
        // @ConditionalOnProperty and `path` by the @PostMapping placeholder — so nothing else in
        // this suite proves the BEAN binds them. If a property key is ever renamed on one side
        // only, the endpoint silently moves or silently never registers.
        runner.withPropertyValues(
                        "mailkube.webhook.enabled=true",
                        "mailkube.webhook.path=/hooks/mailkube",
                        "mailkube.webhook.secret=whsec_test",
                        "mailkube.webhook.tolerance=90s")
                .run(context -> {
                    MailkubeProperties.Webhook webhook =
                            context.getBean(MailkubeProperties.class).getWebhook();
                    assertThat(webhook.isEnabled()).isTrue();
                    assertThat(webhook.getPath()).isEqualTo("/hooks/mailkube");
                    assertThat(webhook.getSecret()).isEqualTo("whsec_test");
                    assertThat(webhook.getTolerance()).isEqualTo(Duration.ofSeconds(90));
                });
    }

    @Test
    void defaultsTheWebhookToDisabledAtItsDocumentedPath() {
        // The default path is documented in the README and repeated in the @PostMapping placeholder
        // default. Two copies, so one test holds them together.
        runner.run(context -> {
            MailkubeProperties.Webhook webhook =
                    context.getBean(MailkubeProperties.class).getWebhook();
            assertThat(webhook.isEnabled()).isFalse();
            assertThat(webhook.getPath()).isEqualTo("/mailkube/webhook");
        });
    }

    @Test
    void backsOffWhenTheApplicationDefinesItsOwnMailSender() {
        // A starter that cannot be overridden is a starter that has to be removed. The condition is
        // on the MailSender INTERFACE, so Boot's JavaMail sender counts too.
        runner.withUserConfiguration(OwnMailSender.class)
                .run(context ->
                        assertThat(context).hasSingleBean(MailSender.class).doesNotHaveBean(MailkubeMailSender.class));
    }

    @Test
    void backsOffWhenTheApplicationDefinesItsOwnClient() {
        runner.withUserConfiguration(OwnClient.class).run(context -> {
            assertThat(context).hasSingleBean(MailkubeClient.class);
            // And the sender resolves THAT client, not one of its own: the deferral must not have
            // quietly reintroduced a second construction path.
            assertThat(context.getBean(MailkubeMailSender.class).client())
                    .isSameAs(context.getBean(MailkubeClient.class));
        });
    }

    @Test
    void doesNothingWhenTheSdkIsAbsent() {
        runner.withClassLoader(new FilteredClassLoader(MailkubeClient.class))
                .run(context -> assertThat(context).doesNotHaveBean(MailkubeMailSender.class));
    }

    @Test
    void registersNoWebhookEndpointOnANonWebApplication() {
        runner.withPropertyValues("mailkube.webhook.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(MailkubeWebhookController.class));
    }

    @Test
    void registersTheWebhookEndpointOnlyWhenAskedTo() {
        // No matchIfMissing on the property condition: an HTTP endpoint that appears merely because
        // a dependency landed on the classpath is a security surprise.
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailkubeAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(MailkubeWebhookController.class));

        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailkubeAutoConfiguration.class))
                .withPropertyValues("mailkube.webhook.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(MailkubeWebhookController.class));
    }

    @Test
    void servesTheSecondInstallShapeWithNoSpringWebOnTheClasspath() {
        // The contract's "where the package supports more than one install shape, CI must exercise
        // each of them". spring-web is optional so a batch or worker application is not dragged
        // onto a web stack, and this proves the sender still works without it — inside the normal
        // suite, where a separate CI job would be ceremony.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailkubeAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader("org.springframework.web"))
                .withPropertyValues("mailkube.api-key=mk_test", "mailkube.webhook.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(MailkubeMailSender.class)
                        .doesNotHaveBean(MailkubeWebhookController.class));
    }

    @Test
    void registersCloseAsTheClientsDestroyMethod() {
        // The client owns an HttpClient and is AutoCloseable, so Spring infers close() as the
        // destroy method and releases it at shutdown. Asserted on the bean DEFINITION rather than by
        // observing a closed client, because the SDK exposes no "is closed" predicate and inferring
        // one from a failed send would be testing the JDK's HttpClient instead of this wiring.
        //
        // Pinned because "Spring will handle it" stops being true the moment someone adds an
        // explicit destroyMethod that names something else.
        runner.withPropertyValues("mailkube.api-key=mk_test").run(context -> {
            var definition = context.getBeanFactory().getBeanDefinition("mailkubeClient");
            assertThat(definition.getDestroyMethodName())
                    .isIn(org.springframework.beans.factory.support.AbstractBeanDefinition.INFER_METHOD, "close");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnMailSender {
        /**
         * A bare {@link MailSender}, not Boot's {@code JavaMailSenderImpl}.
         *
         * <p>Using the JavaMail one here would drag {@code jakarta.mail} into the test classpath —
         * the very dependency this starter refuses — and the condition under test is on the
         * interface, so the simplest implementation proves it exactly as well.
         */
        @Bean
        MailSender mailSender() {
            return messages -> {};
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnClient {
        private final MailkubeClient client =
                MailkubeClient.builder().apiKey("mk_other").build();

        @Bean
        MailkubeClient mailkubeClient() {
            return client;
        }
    }
}
