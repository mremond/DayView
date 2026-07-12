package fr.dayview.app

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal class FocusResumeDetector(
    private val interruptionThreshold: Duration = 15.seconds,
) {
    private var hasObserved = false
    private var previousObservation: Instant? = null
    private var focusWasActive = false

    fun observe(isFocusActive: Boolean, now: Instant): Boolean {
        val previous = previousObservation
        val recoveredExistingSession = !hasObserved && isFocusActive
        val resumedAfterInterruption = hasObserved &&
            focusWasActive &&
            isFocusActive &&
            previous != null &&
            now - previous >= interruptionThreshold

        hasObserved = true
        previousObservation = now
        focusWasActive = isFocusActive
        return recoveredExistingSession || resumedAfterInterruption
    }
}

internal class FocusDriftDetector(
    private val switchThreshold: Int = 4,
    private val observationWindow: Duration = 45.seconds,
    private val initialGrace: Duration = 30.seconds,
    private val reminderCooldown: Duration = 5.minutes,
    private val sustainedOffGoal: Duration = 120.seconds,
    private val dayViewBundleId: String = DAYVIEW_BUNDLE_ID,
) {
    private val switchTimes = ArrayDeque<Instant>()
    private var wasActive = false
    private var activeSince: Instant = Instant.DISTANT_PAST
    private var lastBundleId: String? = null
    private var nextReminderAt: Instant = Instant.DISTANT_PAST
    private var offGoalSince: Instant? = null

    fun observe(
        isFocusActive: Boolean,
        now: Instant,
        frontmostBundleId: String?,
        onGoalBundleIds: Set<String> = emptySet(),
    ): Boolean {
        if (!isFocusActive) {
            reset()
            return false
        }
        if (!wasActive) {
            wasActive = true
            activeSince = now
            lastBundleId = frontmostBundleId.takeUnless { it == dayViewBundleId }
            offGoalSince = null
            return false
        }
        val pastGrace = now - activeSince >= initialGrace
        val fired = churnFired(now, frontmostBundleId, pastGrace) ||
            sustainedFired(now, frontmostBundleId, onGoalBundleIds, pastGrace)
        if (fired) {
            switchTimes.clear()
            offGoalSince = null
            nextReminderAt = now + reminderCooldown
        }
        return fired
    }

    private fun churnFired(now: Instant, frontmostBundleId: String?, pastGrace: Boolean): Boolean {
        if (frontmostBundleId.isNullOrBlank() || frontmostBundleId == dayViewBundleId) return false
        val previousBundleId = lastBundleId
        lastBundleId = frontmostBundleId
        if (previousBundleId == null || previousBundleId == frontmostBundleId) return false
        if (!pastGrace) return false
        switchTimes.addLast(now)
        while (switchTimes.isNotEmpty() && now - switchTimes.first() > observationWindow) {
            switchTimes.removeFirst()
        }
        return switchTimes.size >= switchThreshold && now >= nextReminderAt
    }

    private fun sustainedFired(
        now: Instant,
        frontmostBundleId: String?,
        onGoalBundleIds: Set<String>,
        pastGrace: Boolean,
    ): Boolean {
        if (onGoalBundleIds.isEmpty()) {
            offGoalSince = null
            return false
        }
        when (classifyFrontmost(frontmostBundleId, onGoalBundleIds, dayViewBundleId)) {
            OnGoalState.ON_GOAL -> {
                offGoalSince = null
                return false
            }
            OnGoalState.NEUTRAL -> return false
            OnGoalState.OFF_GOAL -> {
                val since = offGoalSince ?: now.also { offGoalSince = it }
                return pastGrace &&
                    now - since >= sustainedOffGoal &&
                    now >= nextReminderAt
            }
        }
    }

    private fun reset() {
        wasActive = false
        activeSince = Instant.DISTANT_PAST
        lastBundleId = null
        switchTimes.clear()
        nextReminderAt = Instant.DISTANT_PAST
        offGoalSince = null
    }
}

internal class MacFrontmostApplicationProvider {
    fun bundleIdentifier(): String? = runCatching {
        if (!isMacOS) return null
        val workspace = message(classPointer("NSWorkspace"), "sharedWorkspace") ?: return null
        val application = message(workspace, "frontmostApplication") ?: return null
        val bundleIdentifier = message(application, "bundleIdentifier") ?: return null
        val utf8String = message(bundleIdentifier, "UTF8String") ?: return null
        utf8String.getString(0, Charsets.UTF_8.name())
    }.getOrNull()

    private fun classPointer(name: String): Pointer? = runtime.objc_getClass(name)

    private fun message(receiver: Pointer?, selector: String): Pointer? {
        if (receiver == null || Pointer.nativeValue(receiver) == 0L) return null
        return runtime.objc_msgSend(receiver, runtime.sel_registerName(selector))
    }

    // JNA maps methods to native symbols by name, so these must match the C runtime.
    @Suppress("ktlint:standard:function-naming")
    private interface ObjectiveCRuntime : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_msgSend(receiver: Pointer, selector: Pointer): Pointer?
    }

    private companion object {
        val isMacOS: Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
        val runtime: ObjectiveCRuntime by lazy { Native.load("objc", ObjectiveCRuntime::class.java) }
    }
}
