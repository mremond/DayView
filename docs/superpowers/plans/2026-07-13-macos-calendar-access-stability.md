# macOS calendar access stability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the macOS calendar (TCC) grant across dev rebuilds and relaunches by giving the EventKit helper a stable Developer ID code identity and a stable, content-addressed extraction path.

**Architecture:** A shared desktop utility extracts bundled helper binaries to a fixed, content-hashed path under `~/Library/Application Support/DayView/helpers/` instead of a random temp file. A Gradle step code-signs the EventKit helper with a Developer ID identity read from `local.properties`, keeping its Designated Requirement identical across rebuilds; when no identity is configured the build is unchanged. TCC then recognises the same signed program run after run.

**Tech Stack:** Kotlin Multiplatform (desktopMain / JVM), Gradle Kotlin DSL, macOS `codesign`, Swift EventKit helper.

## Global Constraints

- ktlint is enforced: `./gradlew ktlintCheck` must pass. Use explicit imports (no wildcards).
- Full pre-commit check: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` green, no stderr.
- Never reference Claude/Anthropic/AI in commit messages; describe the change only. No test-plan or verification sections in commit messages.
- Signing identity must come from `local.properties` (key `dayview.macos.signingIdentity`); never hard-code it. Absent identity or non-macOS host → skip signing, preserve current behaviour.
- Helper resource names on the classpath: `/macos-eventkit-helper`, `/macos-focus-status-helper`. The desktop resources srcDir is `build/generated/macosFocusStatusHelper`.
- Fixed code-signing identifier: `fr.dayview.app.eventkit-helper` (matches `scripts/MacEventKitHelper-Info.plist` `CFBundleIdentifier`).

---

### Task 1: Shared content-addressed helper extraction

Replace the two random-temp-file extraction routines with one shared utility that writes each bundled helper to a stable, content-hashed path. This removes the duplicated logic and gives both helpers a stable path for TCC.

**Files:**
- Create: `composeApp/src/desktopMain/kotlin/fr/dayview/app/MacHelpers.kt`
- Create (test): `composeApp/src/desktopTest/kotlin/fr/dayview/app/MacHelpersTest.kt`
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/CalendarSource.desktop.kt` (replace `extractHelper()`, lines 101-118)
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/MacFocusStatusItem.kt` (replace `extractHelper()`, lines 62-79)

**Interfaces:**
- Produces:
  - `internal fun helperFileName(resourceName: String, bytes: ByteArray): String` — pure; returns `"<basename>-<8-byte-sha256-hex>"` with any leading `/` stripped from `resourceName`.
  - `internal object MacHelpers { fun extract(resourceName: String): java.nio.file.Path }` — copies the classpath resource to `~/Library/Application Support/DayView/helpers/<helperFileName>` (owner rwx), reusing the file when it already exists and is executable.

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/MacHelpersTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MacHelpersTest {
    @Test
    fun `same bytes yield the same file name`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertEquals(
            helperFileName("/macos-eventkit-helper", bytes),
            helperFileName("/macos-eventkit-helper", bytes),
        )
    }

    @Test
    fun `different bytes yield different file names`() {
        assertNotEquals(
            helperFileName("/macos-eventkit-helper", byteArrayOf(1, 2, 3)),
            helperFileName("/macos-eventkit-helper", byteArrayOf(4, 5, 6)),
        )
    }

    @Test
    fun `leading slash is stripped and hash is appended`() {
        val name = helperFileName("/macos-eventkit-helper", byteArrayOf(0))
        assertTrue(name.startsWith("macos-eventkit-helper-"), "was: $name")
        // basename + '-' + 16 hex chars (8 bytes)
        assertEquals("macos-eventkit-helper-".length + 16, name.length)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.MacHelpersTest"`
Expected: FAIL — compilation error, `helperFileName` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `composeApp/src/desktopMain/kotlin/fr/dayview/app/MacHelpers.kt`:

```kotlin
package fr.dayview.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

/** Nom de fichier stable et adressé par contenu pour un binaire accessoire empaqueté. */
internal fun helperFileName(resourceName: String, bytes: ByteArray): String {
    val base = resourceName.trimStart('/')
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val hash = digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    return "$base-$hash"
}

/**
 * Extrait un binaire accessoire empaqueté vers un emplacement stable sous
 * ~/Library/Application Support/DayView/helpers/. Le même binaire retombe
 * toujours sur le même chemin, ce qui évite de re-extraire à chaque lancement
 * et garde une identité de chemin constante pour TCC (macOS).
 */
internal object MacHelpers {
    fun extract(resourceName: String): Path {
        val bytes = checkNotNull(javaClass.getResourceAsStream(resourceName)) {
            "Bundled helper is missing from application resources: $resourceName"
        }.use { it.readBytes() }
        val target = helpersDirectory().resolve(helperFileName(resourceName, bytes))
        if (Files.isExecutable(target)) return target
        Files.write(target, bytes)
        Files.setPosixFilePermissions(
            target,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        return target
    }

    private fun helpersDirectory(): Path {
        val dir = Paths.get(
            System.getProperty("user.home"),
            "Library",
            "Application Support",
            "DayView",
            "helpers",
        )
        Files.createDirectories(dir)
        return dir
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.MacHelpersTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Migrate the EventKit caller**

In `composeApp/src/desktopMain/kotlin/fr/dayview/app/CalendarSource.desktop.kt`, replace the whole `extractHelper()` method (lines 101-118) with a delegation to the shared utility:

```kotlin
    private fun extractHelper(): java.nio.file.Path {
        helperPath?.let { if (Files.exists(it)) return it }
        return MacHelpers.extract("/macos-eventkit-helper").also { helperPath = it }
    }
```

Then remove the now-unused imports at the top of the file: `java.nio.file.StandardCopyOption` and `java.nio.file.attribute.PosixFilePermission` (leave `java.nio.file.Files`, which is still used).

- [ ] **Step 6: Migrate the focus-status caller**

In `composeApp/src/desktopMain/kotlin/fr/dayview/app/MacFocusStatusItem.kt`, replace the whole `extractHelper()` method (lines 62-79) with:

```kotlin
    private fun extractHelper(): java.nio.file.Path {
        helperPath?.let { if (Files.exists(it)) return it }
        return MacHelpers.extract("/macos-focus-status-helper").also { helperPath = it }
    }
```

Then remove the now-unused `java.nio.file.attribute.PosixFilePermission` import if it is no longer referenced elsewhere in the file (keep `java.nio.file.Files`).

- [ ] **Step 7: Run full checks**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no stderr. If ktlint flags an unused import, remove it and re-run.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/desktopMain/kotlin/fr/dayview/app/MacHelpers.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/MacHelpersTest.kt \
        composeApp/src/desktopMain/kotlin/fr/dayview/app/CalendarSource.desktop.kt \
        composeApp/src/desktopMain/kotlin/fr/dayview/app/MacFocusStatusItem.kt
git commit -m "Extract macOS helpers to a stable content-addressed path"
```

---

### Task 2: Sign the EventKit helper with a configurable Developer ID identity

Read the signing identity from `local.properties` and code-sign the EventKit helper at build time with a fixed identifier, so its Designated Requirement is stable across rebuilds. Signing writes to a separate output file (never mutating the compiler output in place) so Gradle up-to-date checking stays correct and there is no per-build churn.

**Files:**
- Modify: `composeApp/build.gradle.kts` (identity property near the version block; retarget `compileMacEventKitHelper` output; new `prepareMacEventKitHelper` task; update `desktopProcessResources`/`desktopTestProcessResources` wiring, lines 264-295)
- Modify: `.claude/CLAUDE.md` (document the `local.properties` key)

**Interfaces:**
- Consumes: nothing from Task 1 (build-script only).
- Produces: the resource file `build/generated/macosFocusStatusHelper/macos-eventkit-helper`, code-signed when `dayview.macos.signingIdentity` is set on a macOS host, unsigned otherwise. Resource name on the classpath is unchanged (`/macos-eventkit-helper`).

- [ ] **Step 1: Read the signing identity from local.properties**

In `composeApp/build.gradle.kts`, immediately after the `isMacHost` declaration (line 39-40), add:

```kotlin
// macOS dev signing: reuse a stable code identity so the calendar (TCC) grant
// survives rebuilds. Read from local.properties (per-machine, gitignored). When
// unset, the EventKit helper is left unsigned and behaviour is unchanged.
val macosSigningIdentity: String? =
    java.util.Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }.getProperty("dayview.macos.signingIdentity")?.takeIf { it.isNotBlank() }
```

- [ ] **Step 2: Point the swiftc compile at an unsigned output path**

In `composeApp/build.gradle.kts`, change the EventKit helper output declaration (lines 264-266) so the compiler writes to an `unsigned/` location that is NOT on the resources srcDir:

```kotlin
val macEventKitHelperUnsignedOutput =
    layout.buildDirectory.file("generated/macosEventKitHelperUnsigned/macos-eventkit-helper")
val macEventKitHelperUnsignedFile = macEventKitHelperUnsignedOutput.get().asFile
macEventKitHelperUnsignedFile.parentFile.mkdirs()
```

Then in the `compileMacEventKitHelper` task (lines 267-290), replace every use of `macEventKitHelperOutput` / `macEventKitHelperOutputFile` with the unsigned equivalents:
- `outputs.file(macEventKitHelperOutput)` → `outputs.file(macEventKitHelperUnsignedOutput)`
- the final `-o` argument `macEventKitHelperOutputFile.absolutePath` → `macEventKitHelperUnsignedFile.absolutePath`

- [ ] **Step 3: Add the prepare-and-sign task**

In `composeApp/build.gradle.kts`, directly after the `compileMacEventKitHelper` task block, add a typed task that copies the unsigned helper to the resource path and signs it when an identity is configured. Using an injected `ExecOperations` keeps it configuration-cache safe:

```kotlin
val macEventKitHelperResourceOutput =
    layout.buildDirectory.file("generated/macosFocusStatusHelper/macos-eventkit-helper")

abstract class PrepareEventKitHelper : DefaultTask() {
    @get:org.gradle.api.tasks.InputFile
    abstract val unsignedHelper: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.Input
    @get:org.gradle.api.tasks.Optional
    abstract val signingIdentity: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.OutputFile
    abstract val signedHelper: org.gradle.api.file.RegularFileProperty

    @get:javax.inject.Inject
    abstract val execOps: org.gradle.process.ExecOperations

    @org.gradle.api.tasks.TaskAction
    fun prepare() {
        val source = unsignedHelper.get().asFile
        val target = signedHelper.get().asFile
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
        target.setExecutable(true, false)
        val identity = signingIdentity.orNull
        if (!identity.isNullOrBlank()) {
            execOps.exec {
                commandLine(
                    "codesign",
                    "--force",
                    "--options", "runtime",
                    "--identifier", "fr.dayview.app.eventkit-helper",
                    "--sign", identity,
                    target.absolutePath,
                )
            }
        }
    }
}

val prepareMacEventKitHelper by tasks.registering(PrepareEventKitHelper::class) {
    onlyIf { System.getProperty("os.name").startsWith("Mac", ignoreCase = true) }
    dependsOn(compileMacEventKitHelper)
    unsignedHelper.set(macEventKitHelperUnsignedOutput)
    signedHelper.set(macEventKitHelperResourceOutput)
    if (macosSigningIdentity != null) {
        signingIdentity.set(macosSigningIdentity)
    } else if (isMacHost) {
        logger.lifecycle(
            "DayView: dayview.macos.signingIdentity is unset; " +
                "EventKit helper left unsigned (calendar permission will reset on rebuild).",
        )
    }
}
```

- [ ] **Step 4: Rewire resource processing to depend on the prepare task**

In `composeApp/build.gradle.kts`, update the `desktopProcessResources`/`desktopTestProcessResources` wiring (lines 292-295) so it depends on `prepareMacEventKitHelper` (which itself depends on the compile) instead of the raw compile task:

```kotlin
tasks.matching { it.name in setOf("desktopProcessResources", "desktopTestProcessResources") }.configureEach {
    dependsOn(compileMacFocusStatusHelper)
    dependsOn(prepareMacEventKitHelper)
}
```

- [ ] **Step 5: Verify the helper is produced and (when configured) signed**

Run: `./gradlew :composeApp:desktopProcessResources`
Expected: BUILD SUCCESSFUL; the file `composeApp/build/generated/macosFocusStatusHelper/macos-eventkit-helper` exists.

If your `local.properties` sets `dayview.macos.signingIdentity`, verify the signature:

Run: `codesign --verify --strict --verbose=2 composeApp/build/generated/macosFocusStatusHelper/macos-eventkit-helper`
Expected: `...: valid on disk` and `...: satisfies its Designated Requirement` (exit 0).

Confirm the identifier:

Run: `codesign --display --verbose=2 composeApp/build/generated/macosFocusStatusHelper/macos-eventkit-helper 2>&1 | grep Identifier`
Expected: `Identifier=fr.dayview.app.eventkit-helper`

- [ ] **Step 6: Confirm up-to-date behaviour (no per-build churn)**

Run twice: `./gradlew :composeApp:prepareMacEventKitHelper` then again `./gradlew :composeApp:prepareMacEventKitHelper`
Expected: the second run reports the task as `UP-TO-DATE` (inputs unchanged → not re-signed).

- [ ] **Step 7: Document the local.properties key**

In `.claude/CLAUDE.md`, under the `## Commands` section (after the Prerequisites line), add:

```markdown
On macOS, calendar access is granted by the system to a stable code identity. To keep
the permission across rebuilds, set a signing identity in `local.properties`:

    dayview.macos.signingIdentity=Developer ID Application: Your Name (TEAMID)

Find installed identities with `security find-identity -v -p codesigning`. When the key
is unset the EventKit helper is left unsigned and macOS re-prompts on each rebuild.
```

- [ ] **Step 8: Run full checks**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no stderr.

- [ ] **Step 9: Commit**

```bash
git add composeApp/build.gradle.kts .claude/CLAUDE.md
git commit -m "Sign the macOS EventKit helper with a configurable dev identity"
```

---

### Task 3: Manual TCC acceptance (macOS, developer machine)

Signing and TCC behaviour are Keychain- and OS-dependent and cannot be unit-tested. This task is the decisive check; the feature is not considered done until Step 4 is observed. No code changes.

**Files:** none.

- [ ] **Step 1: Ensure an identity is configured**

Confirm `local.properties` contains `dayview.macos.signingIdentity=Developer ID Application: ProcessOne (8L55BDM864)` (or the developer's chosen identity from `security find-identity -v -p codesigning`).

- [ ] **Step 2: First run — grant access once**

Run: `./gradlew :composeApp:run`
In the app, trigger the calendar permission request and click **Allow** (or grant it in System Settings → Privacy & Security → Calendars). Confirm calendar busy time appears on the ring.

- [ ] **Step 3: Rebuild and relaunch**

Recompile the helper and relaunch to simulate a normal dev iteration:

```bash
touch scripts/MacEventKitHelper.swift
./gradlew :composeApp:run
```

- [ ] **Step 4: Confirm the grant persists**

Expected: the app reads the calendar and shows busy time **without** any new permission prompt. If macOS re-prompts, capture `codesign --display --verbose=4` output for the extracted helper under `~/Library/Application Support/DayView/helpers/` and the compiled one, and diagnose before claiming completion.
