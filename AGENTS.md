# Agent Guide

This repository is a **framework integration**, not an SDK: a thin Spring Boot starter over
[`mailkube-java`](https://central.sonatype.com/artifact/com.mailkube/mailkube-java).
The API's wire format, retry policy, error taxonomy and webhook signature scheme all belong to the
SDK. If you are about to write code here that serializes a request body, parses an error envelope or
computes an HMAC, it belongs in the other repository.

## Rule Index

> **Index every rule (required).** Every file in `.rules/` MUST have a row in the table below. When
> you add or rename a `.rules/` file, add or update its row in the **same change** — an unindexed
> rule is invisible. `scripts/check-rule-index.sh` enforces this in the `docs` CI job.

| Rule File | Load When |
|---|---|
| `.rules/SOLID_DRY_KISS.md` | Any change at all: the four quality pillars and the local gate commands |
| `.rules/INTEGRATION_CONTRACT.md` | Touching the adapter, the payload mapping, the settings surface or the webhook entry point |
| `.rules/SPRING_BOOT_INTEGRATION.md` | Anything Spring-specific: auto-configuration, conditions, the `MailSender` surface, the package and module naming, the version matrix |
| `.rules/SDK_CONTRACT.md` | Understanding what the SDK guarantees, before assuming a capability exists |
| `.rules/RELEASE.md` | Releasing, versioning, or changing anything under `.github/workflows/` |
| `.rules/CI_GATES.md` | Adding or changing a CI job, especially anything on the release path |

`INTEGRATION_CONTRACT.md`, `SDK_CONTRACT.md` and `CI_GATES.md` are **maintained centrally** and are
byte-identical across every mailkube repository. Do not edit them here: the change would be reverted
the next time the generator syncs them. Open an issue instead.

There is deliberately **no `SDK_DESIGN.md`**: this package designs no API.

## Layout

```
src/main/java/com/mailkube/spring/
  MailkubeAutoConfiguration.java   the one config module: settings -> client, and every condition
  MailkubeProperties.java          the settings surface, bound from mailkube.*
  MailkubeMailSender.java          the extension point, and the error translation
  MailkubePayload.java             the one payload module: SimpleMailMessage -> SDK arguments
  MailkubeWebhookController.java   the inbound entry point (opt-in, no cryptography)
  MailkubeWebhookEvent.java        one Spring event carrying the SDK's typed event
  Version.java                    this artifact's own version, for the User-Agent suffix
src/main/resources/META-INF/spring/
  ...AutoConfiguration.imports    how Boot finds the auto-configuration. Wrong path = silently nothing.
```

## Before you change anything

1. **Does this exist only because of Spring?** If not, it belongs in the SDK. That is the whole test.
2. **Read `.rules/SPRING_BOOT_INTEGRATION.md`.** Several decisions here look arbitrary and are not:
   the package name, the absent `module-info.java`, `MailSender` over `JavaMailSender`, the
   by-name auto-configuration ordering, and the deferred client. Each was verified against the jars,
   and each has a test pinning it.
3. **Run the gates locally.** `.rules/SOLID_DRY_KISS.md` lists them.

## No `examples/`

Every entry point here needs a host application before it can run, so the wiring lives in the README
instead. This is a deliberate departure from `SDK_CONTRACT.md`, recorded in
`INTEGRATION_CONTRACT.md`, and `ci.yml` says so where the example-compilation job would be.
