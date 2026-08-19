# Release & Publishing

Load this when touching `release.yml`, `.releaserc.json`, `gradle.properties`, or the Maven Central
publish flow.

## The contract

1. **Conventional Commits drive the version.** On push to `main`, `semantic-release` reads the commit
   history since the last tag: `fix:` → patch, `feat:` → minor, `feat!:`/`BREAKING CHANGE:` → major.
   `perf:` also releases. Anything else (`chore`, `docs`, `ci`, `refactor`, `test`) does **not** release.
2. **It creates the tag `vX.Y.Z` and the GitHub Release, and commits nothing.** No
   `chore(release):` commit, no `CHANGELOG.md`, no version bump landed in the tree. See "Why nothing
   is committed back to `main`".
3. **The tag IS the version.** `@semantic-release/exec`'s `publishCmd` runs
   `./gradlew publish -Pversion=X.Y.Z`. Gradle writes that version into the jar manifest **and into
   a generated `com/mailkube/version.properties`
   resource**, and `Version.current()` reads it back for the User-Agent, so the version on the wire
   equals the released version by construction.
   The resource is read first and is not redundant: `java.lang.Package` carries no versioning
   information for a package in a *named* module, so on the module path the manifest attribute is
   invisible and a JPMS consumer would report `0.0.0`. Both are derived from the one Gradle version
   property and neither is committed, so this is one source of truth with two carriers.
   The `version=` line in `gradle.properties` is a permanent `0.0.0` placeholder. A local build
   therefore produces `0.0.0` and a published artifact carries the real version: **that is
   intended.** Do not "fix" it by hardcoding a number, in `gradle.properties` or in Java source.
4. **`publishCmd` uploads the signed artifacts and then promotes them**, and only when the commits
   actually warrant a release. Two commands, one `&&`: `./gradlew publish -Pversion=X.Y.Z` followed
   by `./scripts/promote-central.sh com.mailkube`. That placement is deliberate twice
   over. A workflow step after `semantic-release` runs on *every* push to `main`, including the ones
   that release nothing, and would upload the `0.0.0` placeholder to Central — which cannot be
   undone. And the promote has to happen on the machine that uploaded; see the next section.

## `publish` needs a repository, and its silence is the failure mode

Gradle's `maven-publish` needs two things: a **publication** (what to upload) and a **repository**
(where). With a publication and no repository, `./gradlew publish` has nothing to do, does nothing,
and **exits 0** — a release that goes green and ships no artifact. Nothing warns you.

`build.gradle.kts` therefore declares one, and two details about it are load-bearing:

- **The URL is the Portal's OSSRH Staging API**
  (`https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/`). The
  Central Portal has no plain Maven endpoint; this one accepts an ordinary Maven deploy. It does
  **not** turn that deploy into a Portal deployment — see the next section.
- **The repository is named `central` on purpose.** `PasswordCredentials` resolves
  `<repositoryName>Username` / `<repositoryName>Password` project properties, so the name is what
  makes `ORG_GRADLE_PROJECT_centralUsername` / `centralPassword` in `release.yml` reach it. Rename
  the repository and the credentials are silently not found.

## Uploading is only half of it, and the other half is IP-bound

`./gradlew publish` leaves the artifacts in an **open staging repository**. That is not a Portal
deployment: it does not appear on <https://central.sonatype.com/publishing/deployments>, there is no
UI anywhere that lists it, and nothing can be published from it. The release goes green and ships
nothing. This is the same class of silent failure as the missing `repositories` block above, one
layer further along, and it is why `scripts/promote-central.sh` exists.

**The promote must run from the machine that uploaded.** The API keys the default repository by
`(token user, source IP)`, so calling it from anywhere else answers
`No repository found for <user>/<ip>/<namespace>--default-repository` while the repository plainly
exists. A laptop cannot finish a release the runner started. Concretely: the promote lives inside
`publishCmd`, in the same step and on the same runner as the Gradle upload. Do not "tidy" it into a
following workflow step, and do not move it to a second job.

If a release ever does strand a repository — the promote failed, or an older release predates this
script — it is recoverable by hand, but only by naming the repository explicitly rather than using
the default:

```bash
AUTH=$(printf '%s:%s' "$USER" "$TOKEN" | base64 | tr -d '\n')
# `ip=any` is load-bearing: the search is IP-scoped too, and without it this returns [].
curl -sS -H "Authorization: Bearer $AUTH" \
  'https://ossrh-staging-api.central.sonatype.com/manual/search/repositories?ip=any&state=open'
# then POST /manual/upload/repository/<URL-ENCODED KEY> — the key contains slashes.
```

**A promoted deployment publishes itself.** The script asks for `publishing_type=automatic`, so a
deployment that validates goes straight to Maven Central with no human step. There is therefore no
point at which a release can still be dropped: once it is published the version is immutable and
there is no yank, so `ci-gate` in `release.yml` is the entire safety net and must never be weakened.
Change the query parameter in the script to `user_managed` to put a human back in the loop. Either
way, check the Portal after a release rather than assuming a green job means the artifact is on
Central.

## Why nothing is committed back to `main`

`main` is covered by a ruleset requiring a pull request and the gated checks. A `chore(release):`
commit pushed straight to `main` by the workflow violates it, and the obvious fix does not exist:
**`github-actions[bot]` cannot be added to a ruleset bypass list.** Bypass is available to admins,
the maintain/write role, teams, GitHub Apps and Dependabot, and the built-in Actions identity is none
of those. Making the commit work would mean introducing a separate identity — a GitHub App or a
deploy key — purely to write a version number that the tag already carries.

So `.releaserc.json` loads neither `@semantic-release/git` nor `@semantic-release/changelog`, and
nothing is written into the tree at all: the version travels as a `-P` property from the tag to
Gradle. **The generated release notes are the changelog**; there is no `CHANGELOG.md` in this repo.

## Maven Central is the outlier, and it is worth saying out loud

**Every other registry in this SDK family supports OIDC trusted publishing. Maven Central does
not.** PyPI, npm and RubyGems each let a workflow exchange a short-lived identity token for a
credential, so those repos store no secrets. Go needs nothing at all.

This repo needs **two long-lived secrets**, and there is no way around it:

| Secret | What it is |
|---|---|
| `CENTRAL_TOKEN_USERNAME` / `CENTRAL_TOKEN_PASSWORD` | A user token from the Central Portal (Account → Generate User Token). Not your portal password. |
| `GPG_PRIVATE_KEY` / `GPG_PASSPHRASE` | An ASCII-armoured private key whose **public** half is published to a keyserver. Central rejects unsigned artifacts. |

Treat them as the highest-value secrets in the repo: rotate on any suspicion, and never echo them
in a workflow step.

## Required one-time setup

- **Verify the `com.mailkube` namespace.** The Central Portal proves ownership of a
  reversed domain via a **DNS TXT record** on that domain. This has real lead time and blocks the
  first publish; start it before you need it.
- **GitHub environment `release`** (Settings → Environments) with protection rules; the `release`
  job runs in it and holds the four secrets above.
- **Publish the GPG public key** to `keyserver.ubuntu.com` (or another Central accepts), or
  verification fails at upload with an unhelpful message.
- **Decide the namespace's publishing mode** in the Portal. The script asks for `automatic`
  explicitly, so it does not depend on the namespace default. See the section above.

## Do not

- Do not bump the `version=` line, move tags, or add a `CHANGELOG.md`, `@semantic-release/git`
  or `@semantic-release/changelog`. All of those reintroduce the commit to `main` that this
  setup exists to avoid.
- Do not add a `version` literal anywhere in Java source, and do not commit the generated
  `version.properties`. `Version` reads the resource and then the manifest for a reason.
- Do not move `./gradlew publish` out of `publishCmd` into a workflow step — see contract point 4.
- Do not split the promote away from the upload, into a later step, a later job, or a human. It is
  IP-bound to the uploader and it is the difference between a green release and a published one.
- Do not remove the `repositories` block or rename the `central` repository — both failures are
  silent, and the second one only shows up as an authentication error at release time.
- Do not publish a snapshot to Central; it does not accept them.
- Do not gate `release.yml` on anything weaker than the full `ci.yml` (`test` + `build` + `floor`
  + `dry` + `docs`). With `publishing_type=automatic` this gate is the only thing standing
  between a merge and an immutable artifact on Maven Central.
