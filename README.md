# mailkube-spring-boot

[![CI](https://github.com/mailkube/mailkube-spring-boot/actions/workflows/ci.yml/badge.svg)](https://github.com/mailkube/mailkube-spring-boot/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.mailkube/mailkube-spring-boot)](https://central.sonatype.com/artifact/com.mailkube/mailkube-spring-boot)
[![Java](https://img.shields.io/badge/java-25%2B-blue.svg)](build.gradle.kts)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Code of Conduct](https://img.shields.io/badge/Contributor%20Covenant-2.1-purple.svg)](CODE_OF_CONDUCT.md)

Spring Boot starter for mailkube.

Send mail through Spring's own `MailSender`, and receive webhooks as Spring application events. This
starter is a thin adapter over [`mailkube-java`](https://central.sonatype.com/artifact/com.mailkube/mailkube-java):
the API, retries, errors and signature verification all live there.

## Requirements

| | Version |
|---|---|
| Java | **25+** |
| Spring Boot | 3.5.0+ (tested on 3.5.0, 4.0.7, 4.1.0) |

**The Java 25 floor comes from the SDK, not from Spring**, and it is worth stating plainly because it
is higher than Boot's own: Boot itself runs on Java 17. The SDK is compiled with a Java 25 toolchain
and no `release` override, so its class files cannot load on an earlier JVM, and compiling this
starter lower would not help. An application on Java 21 can run Spring Boot 4 but cannot use this
starter.

## Install

Replace `X.Y.Z` with the version on the Maven Central badge above.

```kotlin
implementation("com.mailkube:mailkube-spring-boot:X.Y.Z")
```

```xml
<dependency>
    <groupId>com.mailkube</groupId>
    <artifactId>mailkube-spring-boot</artifactId>
    <version>X.Y.Z</version>
</dependency>
```

Auto-configuration does the rest. Set the key:

```properties
mailkube.api-key=mk_live_...
```

or leave it out entirely and let the SDK read `MAILKUBE_API_KEY` from the environment.

That is the whole setup. Everything else on this page is optional.

## Sending

Inject Spring's `MailSender`. Nothing about your code changes:

```java
@Service
class OrderService {

    private final MailSender mail;

    OrderService(MailSender mail) {
        this.mail = mail;
    }

    void confirm(Order order) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("Acme <hello@yourdomain.com>");
        message.setTo(order.customerEmail());
        message.setSubject("Your order shipped");
        message.setText("It is on its way.");
        mail.send(message);
    }
}
```

### The full-fidelity path

`SimpleMailMessage` has no HTML body and no attachments, permanently — that is Spring's type, not a
limit of this starter. For everything else, inject `MailkubeMailSender` and pass the SDK's own
parameters:

```java
@Service
class Newsletter {

    private final MailkubeMailSender mail;

    Newsletter(MailkubeMailSender mail) {
        this.mail = mail;
    }

    void send() {
        mail.send(SendEmailParams.builder(
                        "Acme <hello@yourdomain.com>", List.of("customer@example.com"), "Hello")
                .html("<p>It works!</p>")
                .tags(List.of(new Tag("campaign", "spring")))
                .idempotencyKey("newsletter-2026-01")
                .build());
    }
}
```

Both surfaces are synchronous, because `MailSender` is. To send off the request thread, annotate
your own caller with `@Async`: that is your application's decision, and this starter does not make
it for you.

### Attachments and inline images

The SDK's attachment model carries a filename, content and content type, and **no content id**. An
inline `cid:` image therefore arrives as an ordinary attachment. That is an SDK capability gap
rather than an integration one, and it is named here so it is not discovered in an inbox.

## Errors

Every SDK failure is translated into Spring's own `MailException` hierarchy, which is what makes
`spring-retry`, Spring Integration's mail adapters and your `@ControllerAdvice` handlers work:

| SDK | Spring |
|---|---|
| `AuthenticationException` | `MailAuthenticationException` |
| `ConfigurationException` | `MailPreparationException` |
| everything else | `MailSendException` |

The original exception is always preserved as the cause. Retryability is deliberately not re-encoded
here: a 429 or a 5xx carries the SDK's own type as the cause, and the SDK is where that taxonomy
belongs.

`send(SimpleMailMessage...)` sends each message independently, so a partial failure throws a
`MailSendException` whose `getFailedMessages()` names exactly which ones did not go.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `mailkube.api-key` | `MAILKUBE_API_KEY` | API key |
| `mailkube.base-url` | the SDK's | API base URL |
| `mailkube.timeout` | the SDK's | per-request timeout, e.g. `30s` |
| `mailkube.webhook.enabled` | `false` | map the webhook endpoint |
| `mailkube.webhook.path` | `/mailkube/webhook` | where to map it |
| `mailkube.webhook.secret` | — | signing secret, required when enabled |
| `mailkube.webhook.tolerance` | the SDK's | timestamp skew allowed |

An unset property is **omitted**, never passed as null, so the SDK's own environment fallbacks stay
in charge of anything you do not set here.

Define your own `MailkubeClient` or any `MailSender` bean and this starter
backs off entirely.

## Webhooks

Off by default. An HTTP endpoint that appeared merely because you added a mail dependency would be a
security surprise, so you opt in:

```properties
mailkube.webhook.enabled=true
mailkube.webhook.secret=whsec_...
```

Then listen. One event carries every webhook type; the SDK's sealed event type is what you switch
on:

```java
@Component
class DeliveryListener {

    @EventListener
    void on(MailkubeWebhookEvent received) {
        WebhookEvent event = received.event();
        if (event instanceof EmailBouncedEvent bounced) {
            suppress(bounced.email());
        }
    }
}
```

Listeners are synchronous by default, which is Spring's own default. Add `@Async` to yours if you
want otherwise.

### If you use Spring Security

The webhook endpoint is an unauthenticated POST verified by signature, so CSRF protection rejects it.
**This starter does not modify your `SecurityFilterChain`**, because a library that silently widened
your application's security posture would be far worse than one line of configuration:

```java
http.csrf(csrf -> csrf.ignoringRequestMatchers("/mailkube/webhook"))
    .authorizeHttpRequests(auth -> auth.requestMatchers("/mailkube/webhook").permitAll());
```

## Non-web applications

`spring-web` is an optional dependency. A batch or worker application gets the mail sender without
being pulled onto a web stack, and the webhook controller is simply not registered.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). The design rules this package is held to live in
[`.rules/`](.rules/), and [`AGENTS.md`](AGENTS.md) indexes them.

## License

Apache-2.0. See [LICENSE](LICENSE).
