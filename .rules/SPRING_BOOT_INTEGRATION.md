# Spring Boot Integration: how the shared contract is realized here

Load this alongside [`INTEGRATION_CONTRACT.md`](INTEGRATION_CONTRACT.md), which is the framework-neutral
version and is maintained centrally. **This file records only what is specific to Spring Boot**: the
places where the contract meets a framework constraint, and the deviations it permits when one is
recorded.

Every claim below about a Spring or Boot API was checked against the jars, not against documentation.
Where a version is named, that is the version it was verified on.

## The package is `com.mailkube.spring`, and that is not a preference

The SDK declares JPMS module `com.mailkube` and exports the packages directly under it.
Duplicate module names fail resolution, and a package split across two modules is a hard error on the
module path. So this starter takes a subpackage and never declares a type in the SDK's own.

`StructureTest` pins it, because the failure does not show up here: a consumer with both jars on the
module path is the one who finds out.

## No `module-info.java`, and the reason is not the obvious one

**A Java artifact in this family normally declares its own JPMS module.** This one does not, and
the exception is recorded here rather than left to look like an oversight.
The intent of that rule — the SDK's namespace stays unsplit and uncontested — is satisfied here by
`Automatic-Module-Name: com.mailkube.spring` in the jar manifest, which reserves the module
name without declaring a descriptor.

The plain reading (ship a real `module-info.java`) was tried and rejected on evidence:

- Every Spring artifact this starter compiles against is an **automatic module**. `jar --describe-module`
  reports "No module descriptor found. Derived automatic module." for `spring-context`,
  `spring-boot-autoconfigure`, `spring-web` and `spring-jcl` (checked on Boot 3.5.9 / Framework 6.2.x).
- A `module-info.java` requiring them **compiles cleanly**. The problem appears one step later: `jlink`
  refuses *any* automatic module in the resolved graph, and it fails naming `spring.jcl` — a transitive
  dependency this project does not control.

So a descriptor here would advertise a modularity the artifact cannot deliver, and would keep failing
for a reason no change in this repository can fix. **The fallback, if this is ever revisited:** the day
Spring ships real descriptors, add `module-info.java` declaring `module com.mailkube.spring`
and the rule is satisfiable literally.

## `MailSender`, never `JavaMailSender`

`JavaMailSender` requires `createMimeMessage()` and `send(MimeMessage...)`. Implementing it would mean

1. a `jakarta.mail` dependency, and
2. parsing MIME back into fields to call the API —

which is the second wire format the contract's "one HTTP path" clause exists to prevent. It would also
make `spring.mail.host` and `spring.mail.port` look meaningful when this starter ignores them entirely.

`StructureTest` fails the build if `JavaMailSender` appears anywhere in `src/main`.

**The dependency this needs is `spring-context-support`, not `spring-context`.** Verified:
`org.springframework.mail.MailSender`, `SimpleMailMessage` and the whole `MailException` hierarchy live
in `spring-context-support`, whose own dependencies are core Spring plus micrometer and **no mail
provider**. `JavaMailSender` is in the same jar under `.javamail`, and it is the one that would drag
`jakarta.mail` in.

### The consequence: two surfaces, on purpose

`SimpleMailMessage` carries from, to, cc, bcc, reply-to, subject and a plain-text body. It has no HTML
body, no attachment and no custom header, permanently. So:

- `send(SimpleMailMessage...)` is the **compatibility path** for code already typed against `MailSender`.
- `send(SendEmailParams)` is the **full-fidelity path**, taking the SDK's own parameters.

Do not "fix" the gap by inventing a richer message type here. That is a second mapping to test, and the
contract's payload clause is explicit that two entry points with two mappings have two behaviours.

## Ordering against Boot's own mail auto-configuration is declared **by name**

Boot's `MailSenderAutoConfiguration` is `@ConditionalOnMissingBean(MailSender.class)`, so whichever
configuration runs first wins, and without an explicit order the winner depends on classpath order.

`beforeName` (strings), **not** `before` (classes), and this is load-bearing:

| Boot line | class | jar |
|---|---|---|
| 3.4, 3.5 | `org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration` | `spring-boot-autoconfigure` |
| 4.0 | `org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration` | `spring-boot-mail` |

A `before = ...class` reference names one package, cannot compile against both lines, and is absent
entirely on a Boot 4 application without the mail starter. `beforeName` takes a string, tolerates an
absent class, and lets one artifact support the whole matrix. Both names are listed. Verified that
`beforeName()` exists on `@AutoConfiguration` in 3.4, 3.5 and 4.0 alike.

## The Boot floor is 3.5.0, and it is derived rather than chosen

Spring's `SimpleMetadataReaderFactory` reads a class's bytecode with a bundled ASM in order to
evaluate `@AutoConfiguration`, and the ASM in spring-core 6.2.0 — which Boot 3.4.0 pins — cannot
parse Java 25 class files (major 69). It fails with "ASM ClassReader failed to parse class file",
so this starter's own auto-configuration is unreadable and every context fails to start. Verified
directly: 6.2.0 fails, 6.2.5 and later parse it, and every 7.x parses it. Boot 3.5.0 pins 6.2.7 and
is therefore the first Boot release this starter can run under at all.

The CI matrix is **3.5.0, 4.0.7, 4.1.0** — the floor, plus one release from each newer Boot line.
Boot 3.4 appears in the ordering table below only because that is where the `beforeName` target
class moved; it is not a supported leg.

## The Java floor is inherited from the SDK, not from Spring

**Java 25**, because the SDK's toolchain is 25 with no `release` override, so its class
files cannot load on an earlier JVM. That is far above Boot's own baseline (Boot 3.4, 3.5 and 4.0 all
ship bytecode major 61, which is Java 17) and it is a real adoption constraint: an application on Java
21 can run Spring Boot 4 but cannot use this starter.

State it plainly in the README rather than letting an adopter meet it as a `ClassFormatError`.
Compiling this starter to an older release would not help, because the SDK it calls still targets 25.

**The framework matrix therefore needs no exclusions.** The contract asks that a leg whose framework
major raises the language floor above that leg's language version be excluded rather than quietly
resolved down. Here no Boot version comes close to the Java floor, so the clause is satisfied
vacuously — which is worth saying, because an empty exclusion list otherwise looks like an oversight.

## The webhook endpoint is off by default, and CSRF is documented rather than exempted

- `@ConditionalOnProperty` with **no `matchIfMissing`**: an HTTP endpoint that appears merely because a
  dependency landed on the classpath is a security surprise, and this one accepts unauthenticated POSTs.
- `@RequestBody byte[]`, because the signature is computed over the bytes as received. A DTO parameter
  hands the stream to Jackson and re-serializing changes bytes; a `String` applies a charset decision
  the signature was not computed over.
- **The starter never touches `SecurityFilterChain`.** With Spring Security present its CSRF filter
  rejects the POST before the handler runs, and the fix belongs to the application: a library that
  silently widened an application's security posture because a mail dependency was added would be a
  far worse default. The README carries the one-line ignore.

The path is configurable, so the mapping resolves a property placeholder — annotation values are
compile-time constants and cannot be computed.

## `spring-web` is optional, and that is the second install shape

A batch or worker application gets the mail sender without being pulled onto a web stack, so
`spring-web` is `compileOnly` and the webhook configuration is nested behind `@ConditionalOnWebApplication`.

The contract requires CI to exercise every install shape. Here that is done **inside the normal suite**
with Boot's `FilteredClassLoader` (`AutoConfigurationTest.servesTheSecondInstallShapeWithNoSpringWebOnTheClasspath`)
rather than as a separate CI job, because the runner can remove the package from the classpath in-process.
Do not add a job for it.

## Tests: loopback is what "no network" means here

The SDK's `transport(...)` builder methods are **package-private** — the transport type is in a package
the SDK's module does not export — so from `com.mailkube.spring` they are unreachable, and
widening the SDK's public surface to provide a test seam would be the worse trade.

So `StubServer` binds a real `com.sun.net.httpserver` server on `127.0.0.1:0`. Nothing leaves the
machine, and the real SDK does its real config resolution, request building and response parsing on
every test. That is the contract's "exercise the real SDK over a stub transport" clause, realized the
only way this SDK allows.

Webhook fixtures are signed with the SDK's own `Webhooks.sign(...)`, never a hand-rolled HMAC.

## PMD's coupling rule, scoped

The auto-configuration class names many types by nature. If `pmdMain` fires on coupling there, raise the
threshold **scoped to that class only**, with a comment saying why. Never globally: the rest of the tree
has no such excuse, and a global waiver silently covers the next class that does not deserve it.
