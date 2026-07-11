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
    private val dayViewBundleId: String = "fr.dayview.app",
) {
    private val switchTimes = ArrayDeque<Long>()
    private var wasActive = false
    private var activeSinceMillis = 0L
    private var lastBundleId: String? = null
    private var nextReminderAtMillis = 0L

    fun observe(isFocusActive: Boolean, nowMillis: Long, frontmostBundleId: String?): Boolean {
        if (!isFocusActive) {
            reset()
            return false
        }

        if (!wasActive) {
            wasActive = true
            activeSinceMillis = nowMillis
            lastBundleId = frontmostBundleId.takeUnless { it == dayViewBundleId }
            return false
        }

        if (frontmostBundleId.isNullOrBlank() || frontmostBundleId == dayViewBundleId) return false

        val previousBundleId = lastBundleId
        lastBundleId = frontmostBundleId
        if (previousBundleId == null || previousBundleId == frontmostBundleId) return false
        if (nowMillis - activeSinceMillis < initialGraceMillis) return false

        switchTimes.addLast(nowMillis)
        while (switchTimes.isNotEmpty() && nowMillis - switchTimes.first() > observationWindowMillis) {
            switchTimes.removeFirst()
        }

        if (switchTimes.size < switchThreshold || nowMillis < nextReminderAtMillis) return false

        switchTimes.clear()
        nextReminderAtMillis = nowMillis + reminderCooldownMillis
        return true
    }

    private fun reset() {
        wasActive = false
        activeSinceMillis = 0L
        lastBundleId = null
        switchTimes.clear()
        nextReminderAtMillis = 0L
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
