<!--
PR titles MUST follow Conventional Commits (e.g. `fix(client): ...`) — it is CI-enforced and
becomes the squash-merge commit message. Only feat/fix/perf trigger a release.
-->

## What

<!-- Describe the change in 1–2 sentences. -->

## Why

<!-- The user-visible problem this solves, or the motivation. -->

## Quality checklist

- [ ] `./gradlew spotlessCheck` passes (palantir-java-format)
- [ ] `./gradlew check` passes: PMD, tests, and ≥ 90% line **and** branch coverage
- [ ] `./gradlew javadoc` passes (every public type and method documented)
- [ ] `npx jscpd --config .jscpd.json .` clean (no new duplication)
- [ ] `pmdMain` output contains no "Cannot load ruleset" line (a broken ruleset passes silently)
- [ ] Docs updated (`README.md`) if user-visible

## Engineering standards (SOLID / DRY / KISS)

- [ ] Single-responsibility: new/changed units do one thing; no god-methods
- [ ] No duplication introduced; shared logic extracted (DRY)
- [ ] Public types and methods documented with Javadoc
- [ ] Complexity within limit (no `@SuppressWarnings("PMD...")` complexity waivers added)
- [ ] A new capability adds a seam, never widens one (ISP)
- [ ] `Automatic-Module-Name` still reserves the starter's module name (there is deliberately
      no `module-info.java` — see `.rules/SPRING_BOOT_INTEGRATION.md`)

## Notes

<!-- Optional: screenshots, follow-ups, breaking-change details. -->
