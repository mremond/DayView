# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## Project Overview

DayView is a Kotlin Multiplatform / Compose Multiplatform app that visualises the time
left in the day (Android + macOS desktop; Linux desktop via the release chain). UI and
business logic are shared through Compose Multiplatform. See [README.md](../README.md).

## Commands

```bash
# Run the desktop app
./gradlew :composeApp:run

# Android (device build/install)
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:installDebug

# macOS packaging
./gradlew :composeApp:packageDmg

# Tests and lint (run before every commit)
./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest
```

Prerequisites: JDK 21 (the build uses `jvmToolchain(21)`; Robolectric needs 21 for
compileSdk 36) and the Android SDK.

## Commit Messages and Pull Requests

- Write all commit messages and pull requests in English.
- Be careful with branch management to avoid losing work. `main` is normally protected:
  work through branches and pull requests, then squash-and-merge to `main`. Delete the
  branch after merge to avoid adding commits to an obsolete branch.
- **Small fixes are the exception.** For a contained fix (a few files, no API changes,
  tests passing), the maintainer may decide to commit directly to `main` and push,
  bypassing the PR flow.
- **Never** add a "Generated with Claude Code" footer, a `Co-Authored-By: Claude`
  trailer, or any other reference to Claude, Anthropic, or an AI assistant in commit
  messages. Commit messages describe the change only.
- **Never** add any reference to Claude, Anthropic, or an AI assistant in pull requests.
  Focus the pull request on a clear summary of the change. Do not add test plans, and do
  not add a Verification or tests section to commit messages or pull requests.
- **Do not reference internal working documents** (design specs, implementation plans,
  or anything under `docs/superpowers/`) in commit messages or pull requests. These are
  private planning artifacts; describe the change on its own terms.
- Before committing, verify tests and lint pass without errors or stderr:
  `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`.

## Release Process

Pushing a `v*` tag runs `.github/workflows/release.yml`, which builds and attaches to a
GitHub Release: macOS `.dmg`, Linux `.deb`/`.rpm`/`.AppImage`, Android `.apk`.

- Native packages require a numeric major version >= 1: tag releases as `v1.x`+.
- **Pre-flight before tagging:** tests and lint green; `git log origin/main..HEAD` shows
  only intended commits; no uncommitted or untracked files; the working tree is on the
  commit you mean to tag.
- **Changelog is developer-owned.** When preparing a release, draft user-facing release
  notes from `git log <last-tag>..HEAD` (group into added / changed / fixed / removed,
  skip internal noise), then stop and hand the draft to the developer for review. Do not
  tag until the developer has approved the wording.
- **Verify after tagging:** the workflow run is green; the GitHub Release exists with all
  platform artifacts attached; install and launch at least one binary.
- **Never delete a published tag.** If a release build is broken, fix forward with the
  next patch version, or retag with a suffix (e.g. `v1.2.0-redo`). Throwaway `-test` tags
  used to exercise the pipeline are the exception and should be cleaned up.
- For releases needing a stabilization window, use a `release/X.Y.Z` branch: tag on the
  branch tip, and once verified merge it back with `git merge --no-ff` to `main`, then
  delete the branch.

## Code Style

- Design modular code. Isolate behaviour and write small, reusable functions.
- Avoid duplicated code.
- ktlint is enforced: run `./gradlew ktlintCheck` (or `ktlintFormat` to auto-fix) before
  committing.
