# 2026-09-15 — Introduce a release process and require PRs for all work

**Status:** In progress
**Link:** none (feature request, not a bug) — reference implementation at
`../TaigaMobileNova/.github/workflows/{release,release-prepare,release-finalize}.yml`
**Updated:** 2026-09-15

## Report

User asked directly (no GitHub issue): "let's introduce a release process, and now all tasks
will go through PRs, you can take a look here `/home/gregory/proj/grappim/TaigaMobileNova` where
there is a release process that builds for desktop, but you are free to adapt to whatever is
needed."

Two distinct asks bundled together:
1. A release process (build installers, tag, publish a GitHub release).
2. A workflow-policy change: ordinary work should go through PRs from now on, not direct pushes
   to `master`.

Not stated: target version for the first release, whether Dmg/Msi (already declared in
`composeApp/build.gradle.kts` but never exercised — Mukk is a Linux-only player) should be
touched, or whether `version-code` (declared, unused) should be kept.

## Findings

1. **Mukk has one branch, already protected, PRs already technically possible today.**
   `gh api repos/Grigoriym/Mukk/rulesets/23432196` shows an active `master protection` ruleset:
   `pull_request` required (0 approvals, but required), `required_status_checks` =
   `guardrails` + `build`, non-fast-forward and deletion blocked, owner is a bypass actor. `gh pr
   list --state all` returns zero rows and `git log` shows 20 commits landing straight on
   `master` — every commit to date used the owner's bypass, not a PR. So "go through PRs" is a
   *practice* change for the owner, not a missing technical control — the ruleset already exists,
   it's just been bypassed every time.

2. **No `dev`/long-lived second branch exists**, unlike TaigaMobileNova (`git branch -a` and the
   ruleset's `include: ["refs/heads/master"]` are the only ref pattern). TaigaMobileNova's
   `release-finalize.yml` back-merges `master` into `dev` after tagging — that step has nothing to
   act on in Mukk and must not be ported.

3. **Compose Desktop packaging is already wired for Deb, plus Dmg and Msi.**
   `composeApp/build.gradle.kts:53-70`: `targetFormats(TargetFormat.Deb, TargetFormat.Dmg,
   TargetFormat.Msi)`, plus a `linux { ... }` block (icon, `debMaintainer`, `debPackageVersion`,
   `appCategory`, `menuGroup`, `shortcut`). No `linux.rpmLicenseType`/`rpmPackageVersion` set, and
   `Rpm` is not in `targetFormats`. Confirmed via `javap` on
   `org.jetbrains.compose.desktop.application.dsl.LinuxPlatformSettings` (from the cached
   `compose-gradle-plugin-1.10.0.jar`) that `rpmLicenseType`/`rpmPackageVersion` exist as settable
   fields — `packageRpm` will run without them, but jpackage's own docs say an unset RPM license
   defaults to "unknown," which is a real, low-effort gap to close given `LICENSE` is Apache-2.0.
   CLAUDE.md's project context frames Mukk explicitly as a Linux player ("AIMP broken on Linux" /
   "DeaDBeeF has political issues" motivation) — Dmg (macOS) and Msi (Windows) targets are
   plausible copy-paste from a cross-platform Compose template, not something the project
   description asks for. `Dockerfile`, CI, and every doc in the repo mention Linux only.

4. **`version-code` is declared but dead.** `grep -rn "version-code\|versionCode"` across
   `*.kts`/`*.toml` (excluding `build/`) returns exactly one hit: its own declaration at
   `gradle/libs.versions.toml:2`. It is not read by `composeApp/build.gradle.kts` (which uses
   `libs.versions.version.name.get()` for both `packageVersion` and `debPackageVersion`), nor
   referenced anywhere else in the codebase. It's a leftover from copying the version-block shape
   from an Android project (TaigaMobileNova) where `version-code` is a real Play/F-Droid
   requirement (Android's monotonic `versionCode`). Mukk has no Android target and no app-store
   listing that needs a separate build-number axis from the semver `version-name`.

5. **TaigaMobileNova's `release-prepare.yml` writes two Android-only artifacts Mukk has no
   equivalent for**: F-Droid `fastlane/metadata/.../changelogs/<code>.txt` and
   `playstore/changelogs/<code>.txt` stub files (`release-prepare.yml:45-52`). Neither directory
   exists in Mukk (`find . -iname fastlane -o -iname playstore` → nothing) and there's no
   distribution channel that reads them — porting these stubs would create dead files with no
   consumer.

6. **`check-guardrails.sh` trips unconditionally on any `.github/` path change** (not only
   gate-weakening ones — see `TRIPWIRE_PATHS` including bare `.github/` with a trailing slash,
   `check-guardrails.sh:22-27,59`). New release workflow files under `.github/workflows/` will
   trip this regardless of their content, so the implementation commit(s) need a `Gate-change:`
   trailer per this repo's own existing convention (CLAUDE.md "Changing a check means saying so").
   This is a from-evidence fact about what will happen at commit time, not itself a design
   decision.

7. **`softprops/action-gh-release` (used by TaigaMobileNova) and `actions/upload-artifact`,
   `actions/checkout`, `gradle/actions/setup-gradle`, `actions/setup-java` are all already in use
   somewhere in one of the two repos** — no new third-party Action needs vetting beyond what
   TaigaMobileNova already trusts (`release.yml:34,68,78`; Mukk's own `ci.yml` already uses
   `checkout@v7`, `setup-java@v5`, `setup-gradle@v4`).

8. **CLAUDE.md's "How work happens here" section currently describes the investigate-issue →
   approved doc → parts → close-out flow, but says nothing about branch/PR mechanics** for
   day-to-day work — it assumes commits land (via the parts process) without specifying how they
   reach `master`. A PR-only policy change is a process/documentation fact, not a CI mechanism;
   grep of CLAUDE.md for "PR" only turns up the "Settled decisions" table and the "master is
   protected... PRs required" sentence describing the existing ruleset, not an instruction to
   actually use it. This needs a CLAUDE.md edit, not (only) a workflow file.

## Root cause

Not applicable — this is a feature request, not a defect. Two gaps exist: (a) no release
automation exists yet (first release ever — `git tag` and `gh release list` both empty), and (b)
the existing PR-requiring branch ruleset has never actually been used, because the owner's bypass
access made it optional in practice.

## Impact

Without a release process, publishing an installer today is a fully manual `./gradlew
:composeApp:packageDeb` + hand-uploading to somewhere. Without an enforced PR habit, "review
before merge" exists in the ruleset but not in practice (zero PRs to date) — the stated policy
change closes that gap going forward.

## Open questions

- Target version for the first tagged release (currently `version-name = "1.0.1"` already sitting
  in the toml, ahead of any tag) — not a blocker for building the *process*; the first
  `release-prepare` run will need this input regardless.
- Whether "all tasks go through PRs" should also flip the ruleset's `required_approving_review_count`
  from 0 to something, now that there's an actual reviewer story — sole-maintainer project, likely
  moot, flagged but not recommended as part of this doc's scope (it's an OK adjustment, not
  requested).

## Options

### For version-code (Finding 4)

**A1 — Drop `version-code` entirely (recommended).** Delete the unused key from
`gradle/libs.versions.toml`, keep only `version-name`. `release-prepare` bumps one field.
- Pros: removes genuinely dead config; nothing in Simplicity First tolerates carrying an unused
  version axis just because a reference project has one; one fewer input to `release-prepare`.
- Cons: mild churn on a value that's harmless if left alone; if Mukk ever ships via a channel that
  wants a separate monotonic build number (a Flatpak/Snap listing, say), it'd need to be
  reintroduced later — but that's a new, currently-hypothetical requirement, not a reason to keep
  it now (Simplicity First: no speculative config).
- Risk: none — confirmed zero references outside its own declaration.

**A2 — Keep it, and have `release-prepare` auto-increment it alongside `version-name`,
mirroring TaigaMobileNova exactly.**
- Pros: zero drift from the reference implementation; if a future packaging format wants a build
  number, it's already there and already bumped every release.
- Cons: bumps a value nothing reads, forever, on every release — busywork with no observable
  effect, which is exactly what Simplicity First and "no speculative config/flexibility that
  wasn't requested" argue against.

Recommendation: **A1**. Surgical Changes says "don't remove pre-existing dead code unless asked"
for code found *while doing something else* — but this isn't an incidental discovery, it's a
direct input to the exact release-prepare workflow this doc is designing, so deciding what
`release-prepare` bumps is in scope, not a drive-by cleanup.

### For packaging targets (Finding 3)

**B1 — Add `Rpm` to `targetFormats`, set `rpmLicenseType = "Apache-2.0"` and
`rpmPackageVersion = libs.versions.version.name.get()`; leave `Dmg`/`Msi` exactly as they are
(recommended).**
- Pros: closes the one real gap (no license metadata on the rpm) with a two-line addition; widens
  Linux distro coverage (deb-based + rpm-based) which is squarely in scope for "introduce a
  release process [for a Linux player]"; doesn't touch Dmg/Msi, so no risk of breaking something
  the user may be relying on or plan to use later; matches TaigaMobileNova's own choice to ship
  both deb and rpm.
- Cons: `release.yml` needs `rpm`/`fakeroot` installed on the runner (TaigaMobileNova already
  does this — `apt-get install -y fakeroot rpm`), one more package format to keep working.
- Risk: low — `packageRpm` is a well-trodden Compose Desktop task.

**B2 — Also remove `Dmg`/`Msi`, since Mukk is Linux-only and CLAUDE.md's own reference-project
convention ("Trust their code over their docs... note a drift here") makes this worth flagging.**
- Pros: `targetFormats` reflects reality — no macOS/Windows testing, code signing, or install
  base exists for those formats, so shipping them (or trying to, in `release.yml`) advertises
  support that doesn't exist.
- Cons: **out of scope** for "introduce a release process" — removing already-declared platform
  targets is an independent decision about platform support the user hasn't asked for, and
  Surgical Changes/Think Before Coding both say not to fold an adjacent cleanup into a task that
  didn't ask for it. Worth a one-line note, not a silent deletion.
- Risk: low technically, but scope creep.

Recommendation: **B1**, and note B2 in the doc for the user to decide separately (see below) —
`release.yml` itself will simply not build/attach Dmg/Msi artifacts (nobody's asked for those
outputs and this repo has no runner or signing story for them), without deleting the
`targetFormats` declaration.

### For release trigger shape (Finding — no dedicated finding, general design point)

**C1 — Tag push only.**
- Pros: simplest; "the tag is the trigger" is the conventional GitHub Actions release pattern;
  matches how `release-finalize` produces the tag as the very last automated step, so nothing
  needs a redundant manual trigger.
- Cons: recovering from a failed release run (e.g. transient runner failure) requires re-pushing
  a tag, which is awkward (`git push --delete` + retag) and touches history.

**C2 — Tag push + `workflow_dispatch` with a tag input (recommended, matches TaigaMobileNova).**
- Pros: normal path is fully automatic (tag from `release-finalize` triggers it); a failed run can
  be manually re-triggered against the same existing tag without any git surgery — this is exactly
  why TaigaMobileNova has both.
- Cons: one extra `if:` condition in the workflow; negligible.

Recommendation: **C2** — directly ports a pattern already proven useful in the reference repo, no
adaptation needed.

### For release-finalize's back-merge (Finding 2)

**D1 — Drop the back-merge step entirely (recommended, not really a choice).** `release-finalize`
tags on merge to `master` and stops.
- Pros: correct — there is no second branch to back-merge into.
- Cons: none.

No alternative option exists here; stating it as a finding, not a decision to weigh.

### For "all tasks go through PRs" (Finding 8)

**E1 — Add a short policy paragraph to CLAUDE.md's "How work happens here" section (recommended).**
State plainly: ordinary work (including a single approved `### Part N`) lands via a feature
branch + PR into `master`, relying on the ruleset's existing `guardrails`+`build` checks; direct
pushes to `master` are no longer the default even though the owner's bypass still technically
allows them.
- Pros: makes the policy discoverable to a cold session (the whole point of this file, per its own
  "What this file is not" section); doesn't require touching the ruleset itself, since it's
  already correctly configured — this is a *practice* commitment, not a new control.
- Cons: none really — it's documentation, low blast radius. Enforcement still relies on the owner
  choosing not to bypass; the ruleset can't force that (bypass is inherent to owner-admin rights).
- Risk: none.

**E2 — Also tighten the ruleset (e.g. remove the owner's bypass, or require ≥1 approval).**
- Pros: makes the policy technically unbypassable.
- Cons: sole-maintainer repo — removing owner bypass would mean the owner can never merge their
  own PR without a second reviewer who doesn't exist, effectively freezing `master`. Out of scope:
  the user asked for a release process and a PR habit, not a change to who can merge.
- Risk: high (could lock the owner out of merging entirely) for a benefit not requested.

Recommendation: **E1**. Flagging E2 explicitly so it isn't silently assumed — the user may want
some version of it, but it should be its own decision.

## Decision

User approved all recommended options as-is (2026-09-15): A1 (drop `version-code`), B1 (add Rpm,
leave Dmg/Msi alone), C2 (tag push + workflow_dispatch), D1 (no back-merge), E1 (document PR
policy in CLAUDE.md, no ruleset change).

## Implementation plan

### Part 1 — CLAUDE.md: document the PR-first policy [x]
- Add a short paragraph to "How work happens here" per Option E1.
- **Verify:** manual read-through; no build impact.
- **Landed:** added a paragraph after "One part per session" stating every part lands via a
  feature branch + PR against `master`, gated by the existing ruleset, not the owner's bypass.
  Also updated Close-out step 3 to say commits land via that same PR path.

### Part 2 — Packaging: add Rpm target + license metadata [x]
- `composeApp/build.gradle.kts`: add `TargetFormat.Rpm`, set `rpmLicenseType`/`rpmPackageVersion`
  per Option B1. Drop `version-code` from `gradle/libs.versions.toml` per Option A1 (one commit,
  since both touch the same "what does a release build/version" surface).
- **Verify:** `./gradlew :composeApp:packageDeb :composeApp:packageRpm` succeeds locally (needs
  `fakeroot`/`rpm` installed) and produces both a `.deb` and `.rpm` under
  `composeApp/build/compose/binaries/main*/`.
- **Landed:** exactly as planned. Verified locally — both tasks succeeded
  (`mukk_1.0.1_amd64.deb`, `mukk-1.0.1-1.x86_64.rpm`) and `rpm -qip` on the built package confirms
  `License: Apache-2.0` / `Version: 1.0.1`.

### Part 3 — `.github/workflows/release-prepare.yml` [x]
- `workflow_dispatch` with a `version` input; bump `version-name` on a `release/vX.Y.Z` branch;
  open a PR to `master` (adapted from TaigaMobileNova's `release-prepare.yml`, minus the
  Android/F-Droid/Play changelog stubs per Finding 5, minus `version_code`/`version-code`
  handling per Option A1).
- **Verify:** manual dispatch against a throwaway version once merged; confirm the PR opens with
  the right branch/diff. (CI-shaped verification isn't possible before this exists on `master`.)
- **Landed:** as planned, plus adapted to this repo's 2-space YAML indentation (`ci.yml`/
  `guardrails.yml`'s style, not TaigaMobileNova's 4-space/tab). No `dev`-branch merge step (there
  is none to merge). PR body drops the F-Droid/Play/back-merge lines that don't apply here.
  Manual `workflow_dispatch` verification against a throwaway version is deferred to after this
  merges to `master` (the workflow can't be dispatched from a branch that isn't `master` yet).

### Part 4 — `.github/workflows/release-finalize.yml` [x]
- On `pull_request` `closed`+merged from a `release/v*` branch into `master`: tag `vX.Y.Z` and
  push. No back-merge step (Option D1).
- **Verify:** manual — merge a real release PR once Part 3 exists and confirm the tag appears.
- **Landed:** as planned. No back-merge step, and — because that was the only reason
  TaigaMobileNova's version needs an admin `RELEASE_PAT` (pushing to `dev`, which has its own
  branch protection) — this one needs no such secret: tag refs (`refs/tags/*`) aren't covered by
  the `master` ruleset (`include: ["refs/heads/master"]`), so the default `GITHUB_TOKEN` with
  `contents: write` can push the tag directly. End-to-end verification (merge a real
  `release/v*` PR, confirm the tag appears) is deferred to Part 5's own manual check, since a tag
  with no `release.yml` listening for it yet has nothing to confirm beyond "the tag exists."

### Part 5 — `.github/workflows/release.yml`
- Trigger on tag push or `workflow_dispatch` with a tag input (Option C2). Install
  `fakeroot`/`rpm`, run `./gradlew :composeApp:packageDeb :composeApp:packageRpm`, upload both as
  a GitHub Release via `softprops/action-gh-release` with `generate_release_notes: true` (no
  Android/AAB/APK steps — none exist in Mukk).
- **Verify:** the same manual tag-push/dispatch check as Part 4, confirming a GitHub Release
  appears with both artifacts attached.

Each part is independently landable and each guardrails-tripping commit needs the
`Gate-change:` trailer per Finding 6.
