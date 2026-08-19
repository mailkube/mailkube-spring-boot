# CI Gates: the checks every mailkube repo runs, and why

Load this when adding, removing or weakening a CI job, or when a release fails after the tag was
already pushed.

This file is **language-neutral and shared**. Every mailkube repo carries an identical copy; it is
maintained centrally and changes land everywhere together, so open an issue rather than editing
this copy. The commands differ per ecosystem and live in `.rules/RELEASE.md`, but the *reasons*
below are identical everywhere, and each was learned the expensive way in one repo before being
written down for all of them.

## The release path tags before it publishes, and that ordering sets the rules

`release.yml` computes the version, **pushes the tag and creates the GitHub Release**, and only then
builds and uploads. That order is not negotiable — the tag is the version (see `.rules/RELEASE.md`),
so nothing can be built until it exists.

The consequence: **a packaging failure at release time leaves a tagged version with nothing on the
registry.** Semver forbids reusing the number, and most registries forbid replacing an artifact, so
recovery means burning a version and explaining a gap. Every gate below exists to make that
unreachable.

### 1. Publish-readiness runs on the pull-request path

Whatever the release job will do to produce and validate the artifact, do it on every PR, against
the tree as it stands. Build the artifact, run the registry's own manifest checks, and assert the
package's shape. A `--dry-run` that sends nothing is the right tool where the registry offers one.

**Assert the outcome, not the configuration.** Checking that an include list exists proves nothing;
listing the built artifact and failing when it ships `tests/`, `.github/` or `.rules/` proves the
thing you care about. A partial include list is worse than none, because it reads as "source only"
while it is not.

### 2. The dependency floor is fiction until a job proves it

Every test leg installs the lockfile pins or the newest matching versions, so declared lower bounds
(`>=`, `^`) are never executed by anything. A floor that no longer works then breaks exactly one
person: the consumer whose resolver picks it.

Where the package declares runtime dependencies, add a job that resolves **down** to the oldest
allowed version of each, on the oldest supported language version, and runs the suite. Coverage is
the main job's gate, not this one's — this job answers "do the floors import and behave".

Where the package has **no runtime dependencies**, say so in `ci.yml` rather than leaving the
absence to look like an oversight. A zero-dependency SDK is a deliberate property, and the comment
is what stops someone adding the job later and finding nothing to pin.

### 3. Examples are compiled, or they rot

Examples are excluded from lint, coverage and the duplication gate, because they are documentation
rather than shipped code. The exclusions that make that work also remove them from the normal
build, so an API change breaks every example silently and the first person to notice is a reader
copying code that no longer compiles.

Compile or type-check each example in CI. It is the cheapest gate in this file and the one most
likely to catch a real regression in a published SDK.

### 4. A job-level `permissions:` block replaces the defaults

It does not add to them. Every scope the job needs must be listed, including the ones the workflow
was getting for free before the block was introduced.

For `@semantic-release/github` that means `contents: write` **plus** `issues: write` and
`pull-requests: write`: it comments on the issues and pull requests a release resolves and labels
them `released`. Without those two, the call 403s and the run ends red **after** the tag and the
Release already exist — the exact failure the gates above exist to prevent, arriving through the
permissions system instead.

### 5. Pin the release tool, including the one with no manifest

A repo can sit untouched for months. An unpinned `npx semantic-release` resolves `@latest` at run
time, so a new major can change the config schema or raise the Node floor above the version pinned
in `setup-node`, and releases break with no change in the repo.

Dependabot cannot see that dependency — there is no manifest naming it — so pin the major in the
invocation (`npx --yes -p semantic-release@24`) and treat bumping it as a manual task.

## Do not

- Do not move a publish-readiness check into `release.yml` only. By the time it runs there, the tag
  exists and the failure is already unrecoverable.
- Do not delete a gate to make a change pass. Every one of them is here because it failed for real.
- Do not narrow `permissions:` without checking what the release tool calls.
