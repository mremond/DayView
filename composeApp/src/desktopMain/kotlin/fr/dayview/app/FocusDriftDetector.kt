package fr.dayview.app

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

internal class FocusResumeDetector(
    private val interruptionThresholdMillis: Long = 15_000L,
) {
    private var hasObserved = false
    private var previousObservationMillis = 0L
    private var focusWasActive = false

    fun observe(isFocusActive: Boolean, nowMillis: Long): Boolean {
        val recoveredExistingSession = !hasObserved && isFocusActive
        val resumedAfterInterruption = hasObserved &&
            focusWasActive &&
            isFocusActive &&
            nowMillis - previousObservationMillis >= interruptionThresholdMillis

        hasObserved = true
        previousObservationMillis = nowMillis
        focusWasActive = isFocusActive
        return recoveredExistingSession || resumedAfterInterruption
    }
}

internal class FocusDriftDetector(
    private val switchThreshold: Int = 4,
    private val observationWindowMillis: Long = 45_000L,
    private val initialGraceMillis: Long = 30_000L,
    private val reminderCooldownMillis: Long = 5 * 60_000L,
    private val sustainedOffGoalMillis: Long = 120_000L,
    private val dayViewBundleId: String = "fr.dayview.app",
) {
    private val switchTimes = ArrayDeque<Long>()
    private var wasActive = false
    private var activeSinceMillis = 0L
    private var lastBundleId: String? = null
    private var nextReminderAtMillis = 0L
    private var offGoalSinceMillis: Long? = null

    fun observe(
        isFocusActive: Boolean,
        nowMillis: Long,
        frontmostBundleId: String?,
        onGoalBundleIds: Set<String> = emptySet(),
    ): Boolean {
        if (!isFocusActive) {
            reset()
            return false
        }
        if (!wasActive) {
            wasActive = true
            activeSinceMillis = nowMillis
            lastBundleId = frontmostBundleId.takeUnless { it == dayViewBundleId }
            offGoalSinceMillis = null
            return false
        }
        val pastGrace = nowMillis - activeSinceMillis >= initialGraceMillis
        val fired = churnFired(nowMillis, frontmostBundleId, pastGrace) ||
            sustainedFired(nowMillis, frontmostBundleId, onGoalBundleIds, pastGrace)
        if (fired) {
            switchTimes.clear()
            offGoalSinceMillis = null
            nextReminderAtMillis = nowMillis + reminderCooldownMillis
        }
        return fired
    }

    private fun churnFired(nowMillis: Long, frontmostBundleId: String?, pastGrace: Boolean): Boolean {
        if (frontmostBundleId.isNullOrBlank() || frontmostBundleId == dayViewBundleId) return false
        val previousBundleId = lastBundleId
        lastBundleId = frontmostBundleId
        if (previousBundleId == null || previousBundleId == frontmostBundleId) return false
        if (!pastGrace) return false
        switchTimes.addLast(nowMillis)
        while (switchTimes.isNotEmpty() && nowMillis - switchTimes.first() > observationWindowMillis) {
            switchTimes.removeFirst()
        }
        return switchTimes.size >= switchThreshold && nowMillis >= nextReminderAtMillis
    }

    private fun sustainedFired(
        nowMillis: Long,
        frontmostBundleId: String?,
        onGoalBundleIds: Set<String>,
        pastGrace: Boolean,
    ): Boolean {
        if (onGoalBundleIds.isEmpty()) {
            offGoalSinceMillis = null
            return false
        }
        when (classifyFrontmost(frontmostBundleId, onGoalBundleIds, dayViewBundleId)) {
            OnGoalState.ON_GOAL -> {
                offGoalSinceMillis = null
                return false
            }
            OnGoalState.NEUTRAL -> return false
            OnGoalState.OFF_GOAL -> {
                val since = offGoalSinceMillis ?: nowMillis.also { offGoalSinceMillis = it }
                return pastGrace &&
                    nowMillis - since >= sustainedOffGoalMillis &&
                    nowMillis >= nextReminderAtMillis
            }
        }
    }

    private fun reset() {
        wasActive = false
        activeSinceMillis = 0L
        lastBundleId = null
        switchTimes.clear()
        nextReminderAtMillis = 0L
        offGoalSinceMillis = null
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
