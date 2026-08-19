# Integration Contract: the decisions every mailkube framework integration implements identically

Load this when touching **anything in a framework-integration package**: the adapter that plugs into
the framework's extension point, the payload mapping, the settings surface, the webhook entry point,
or the startup checks.

This file is **framework-neutral and shared**. Every mailkube framework integration carries an
identical copy, so all of them make the same promises. It is maintained centrally and changes land
in every integration together: open an issue rather than editing this copy, which would only drift.

Its companion is [`SDK_CONTRACT.md`](SDK_CONTRACT.md), which describes the API itself. **This package
adapts that contract; it never re-implements it.** Where a framework genuinely demands a different
shape, the deviation is allowed but must be recorded in this package's own
`.rules/<FRAMEWORK>_INTEGRATION.md`, which is where the framework-specific realization lives.

## What this package is

**A thin adapter, not an SDK.** The wire format, authentication, retry policy, error taxonomy,
pagination and webhook signature scheme all belong to the SDK. If you are writing code here that
serializes a request body, parses an error envelope, or computes an HMAC, you are writing it in the
wrong repository.

The test for whether something belongs here: *does it exist only because of this framework?* Mapping
the framework's mail message onto SDK arguments does. Deciding what an HTTP 429 means does not.

## One HTTP path

**Never subclass a framework base class that opens its own HTTP client and serializes the payload
itself.** Frameworks often offer one, and it is always the wrong choice: it produces a second wire
format that drifts from the SDK's, silently, and the tests for both will pass while they diverge.

Accumulate SDK arguments and call the SDK verb. The consequences are what you want:

- Adding an API field becomes a change in the SDK, then at most a one-line setter here.
- There is exactly one place that knows how a request reaches the server, and it is tested there.

**Identify this package in the User-Agent.** Every SDK takes a suffix (see `SDK_CONTRACT.md`); set
it to this package's own `name/version` when the client is constructed, in the one config module,
so it applies to every request rather than to whichever call site remembered. Without it, traffic
from a framework integration is indistinguishable from direct SDK use, and nobody can tell whether
a change here moved anything. The SDK's own token stays leading: `mailkube-python/1.4.0
mailkube-django/0.2.0`.

The version reported is **this package's**, read from its own single source of truth, never a
literal. A hardcoded suffix version is the same drift the SDK contract forbids, one layer up.

## One payload module, one config module

- **One payload module converts the framework's message type into SDK arguments**, and every entry
  point calls it. A package with two entry points and two mappings has two behaviours, and only one
  of them is the one you tested.
- **One config module maps framework settings onto SDK constructor arguments.** The adapter and any
  startup checks must not be able to disagree about where the API key comes from.
- **An unset setting is omitted, not passed as null.** The SDK has its own environment-variable
  fallbacks and its own validation; passing an explicit null defeats the first and duplicates the
  second. This package does not re-validate configuration the SDK validates.

Resist sharing beyond that. Where two entry points take genuinely different inputs — a raw framework
message versus values the framework has already normalized — forcing them through one function
obscures both. Share the conversion they truly have in common and no more.

## No persistent state, ever

**No models, no migrations, no schema, no tables.** An integration that needs to persist something
has grown past being an adapter, and the feature belongs in a different package. This is also what
keeps the test suite free of a database.

## Errors

**Translate SDK errors into the framework's own error type** at the boundary. Frameworks build
features on their error hierarchy — silent-failure flags, retry policy, error reporting hooks — and
every one of them stops working on an exception type the framework does not recognize. A test pins
this, because the failure is invisible in normal use: the mail simply does not send and nothing
complains.

## Webhooks

**Verify against the raw received bytes.** Every framework offers a parsed body, and using it breaks
verification: parsing and re-serializing changes bytes, and the signature will not match. Find the
framework's accessor for the unmodified request body and use that one.

The SDK already owns signature verification. The entry point here adapts the framework's request
object to it and dispatches the result through whatever the framework's idiomatic notification
mechanism is. It contains no cryptography.

## Flavour

**An integration inherits the flavour of the framework's extension point, not of the SDK it wraps.**
Where the framework's hook is synchronous, the integration is synchronous, even when the SDK offers
an async client. **Never start an event loop, scheduler or reactor to reach an async client from a
synchronous hook** — under a server that is already running one, that raises.

If the framework later ships an async extension point, that is a new class alongside the existing
one, not a change to it.

## Tests

- **Tests must not require a real host application, a database, or network access.** Frameworks
  provide a harness for exactly this; use it.
- **Exercise the real SDK over a stub transport.** Faking the SDK itself only proves the tests agree
  with themselves: it would keep passing after the SDK changed its argument names underneath you.
  The SDK's injectable HTTP client is the seam this relies on.
- **Where the package supports more than one install shape** — an optional adapter behind an extra,
  say — CI must exercise each of them. A path that is only ever installed one way is a promise
  nobody is testing.
- **The framework version is a matrix axis of its own**, separate from the language version. An
  integration is the one package that can be broken by something it does not depend on directly:
  the host framework's next major. Test the versions this package claims to support, and **exclude
  a leg whose framework major raises the language floor above that leg's language version** rather
  than letting the resolver quietly settle on something neither axis asked for.

## Documentation

An integration ships **no `examples/` directory**, which is the one place it departs from
`SDK_CONTRACT.md`. Every entry point here needs a host application to exist before it can run, and
a script that boots one is neither small enough to trust nor runnable from a freshly installed
package. The wiring belongs in the README instead: the configuration block, the call through the
framework's own mail API, the webhook route, and the subscriber.

That removes the example-compilation gate `CI_GATES.md` asks for, so **say so where the job would
have been.** A gate that does not apply and a gate somebody forgot look identical six months later.

## Checklist for exposing a new SDK capability

1. Confirm the capability exists in the SDK first. If it does not, the change starts there.
2. Map the framework's inputs to SDK arguments **in the payload module**, never at a call site.
3. Add or extend the settings in the config module if configuration is involved.
4. Translate any new SDK error type into the framework's error type.
5. Tests: the mapping as a unit, the entry point end to end over a stub transport, and one error
   path proving the translation.
6. A README section, and a row in whatever table documents the settings surface.
7. Run every gate in `.rules/SOLID_DRY_KISS.md` locally before pushing.
