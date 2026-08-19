# Contributing to mailkube-spring-boot

Thanks for helping improve **mailkube-spring-boot**, the Spring Boot starter for
[mailkube](https://mailkube.com). It is a framework integration, not an SDK: the API surface,
error taxonomy and webhook signature scheme all live in
[`mailkube-java`](https://github.com/mailkube/mailkube-java).
Contributions of all kinds are welcome: bug reports, fixes, docs, and features.

By contributing you agree that your contributions are licensed under the project's
[Apache License 2.0](LICENSE) (inbound = outbound). **No CLA and no sign-off are required.**
Please also read our [Code of Conduct](CODE_OF_CONDUCT.md).

## Development setup

Requires a **JDK 25** and Node.js (for the `jscpd` duplication check). Gradle itself comes
from the committed wrapper — do not install one.

```bash
git clone https://github.com/mailkube/mailkube-spring-boot
cd mailkube-spring-boot

./gradlew build
```

If you do not have a JDK 25, let the Gradle toolchain fetch one — it will, given a
toolchain resolver.

## Quality gates

Every change must pass the same checks CI runs (see [.rules/SOLID_DRY_KISS.md](.rules/SOLID_DRY_KISS.md)):

```bash
./gradlew spotlessApply                      # format (palantir-java-format)
./gradlew spotlessCheck                      # formatting gate
./gradlew check                              # PMD + tests + the 90% line/branch coverage gate
./gradlew javadoc                            # documentation builds
npx --yes jscpd@4 --config .jscpd.json .     # duplication (DRY) gate, blocks at > 1%
./scripts/check-rule-index.sh                # every .rules/*.md indexed in AGENTS.md
```

Two things that trip people up, both documented in [.rules/SOLID_DRY_KISS.md](.rules/SOLID_DRY_KISS.md):
`check` runs the coverage gate only because it is explicitly told to, and a PMD ruleset that fails
to load leaves the build green with the gate switched off.

## Branches

`develop` is the integration branch: open pull requests against it, and CI runs on every push to
it. `main` is the release branch — merging `develop` into it is what cuts a version, so nothing
lands there except through that merge. See [.rules/RELEASE.md](.rules/RELEASE.md).

Dependency updates target `develop` for the same reason. Their configuration names the branch
explicitly, and a branch that does not resolve produces no pull requests at all, with no error —
so if updates go quiet, check that `develop` still exists before looking anywhere else.

## Commit & PR conventions

This project follows **[Conventional Commits](https://www.conventionalcommits.org/)**. A CI check
enforces the **PR title** (PRs are **squash-merged** using it), and it drives releases: only
`feat:`, `fix:`, and `perf:` cut a new version. See [.rules/RELEASE.md](.rules/RELEASE.md).

That check reports as **`PR-title`**, and that is the name to use when requiring it as a status
check on a branch ruleset — a ruleset naming anything else waits forever on a check nobody reports.

Suggested scopes: `autoconfigure`, `sender`, `webhook`, `ci`, `deps`, `docs`.

```
feat(sender): map reply-to onto the SDK parameters
fix(webhook): reject a delivery whose timestamp is outside the tolerance
docs: document the second install shape
```

## Reporting bugs / requesting features

Open an issue using the templates. For **security vulnerabilities**, do not open a public
issue — follow [SECURITY.md](SECURITY.md) instead.
