package fr.dayview.app

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/** Enumerates regular (foreground) macOS apps for the on-goal app picker. */
class MacRunningApplicationsProvider {
    fun runningApps(): List<AppRef> = runCatching {
        if (!isMacOS) return emptyList()
        val workspace = msg(cls("NSWorkspace"), "sharedWorkspace") ?: return emptyList()
        val apps = msg(workspace, "runningApplications") ?: return emptyList()
        val count = msgLong(apps, "count")
        (0 until count).mapNotNull { index ->
            val app = msgIndex(apps, index) ?: return@mapNotNull null
            // NSApplicationActivationPolicyRegular == 0. Note: objc_msgSend legitimately
            // returns a null Pointer when the underlying value is 0, so this must be read
            // via msgLong (which treats a null return as 0) rather than treated as failure.
            val policy = msgLong(app, "activationPolicy")
            if (policy != 0L) return@mapNotNull null
            val bundleId = readString(app, "bundleIdentifier") ?: return@mapNotNull null
            if (bundleId.isBlank()) return@mapNotNull null
            val name = readString(app, "localizedName") ?: bundleId
            AppRef(bundleId, name)
        }.distinctBy { it.bundleId }.sortedBy { it.displayName.lowercase() }
    }.getOrDefault(emptyList())

    private fun cls(name: String): Pointer? = runtime.objc_getClass(name)

    private fun msg(receiver: Pointer?, selector: String): Pointer? {
        if (receiver == null || Pointer.nativeValue(receiver) == 0L) return null
        return runtime.objc_msgSend(receiver, runtime.sel_registerName(selector))
    }

    /**
     * Same as [msg] but for selectors returning a scalar (count / activationPolicy).
     * A null [Pointer] from objc_msgSend is a legitimate encoding of the value 0
     * (e.g. NSApplicationActivationPolicyRegular), not a failed call, so unlike [msg]
     * this does not collapse null into "no result" for the caller.
     */
    private fun msgLong(receiver: Pointer?, selector: String): Long {
        if (receiver == null || Pointer.nativeValue(receiver) == 0L) return 0L
        val result = runtime.objc_msgSend(receiver, runtime.sel_registerName(selector))
        return result?.let { Pointer.nativeValue(it) } ?: 0L
    }

    private fun msgIndex(array: Pointer?, index: Long): Pointer? {
        if (array == null) return null
        return runtime.objc_msgSend(array, runtime.sel_registerName("objectAtIndex:"), index)
    }

    private fun readString(receiver: Pointer, selector: String): String? {
        val value = msg(receiver, selector) ?: return null
        val utf8 = msg(value, "UTF8String") ?: return null
        return utf8.getString(0, Charsets.UTF_8.name())
    }

    // JNA maps methods to native symbols by name; the index overload still binds objc_msgSend
    // (same C symbol, extra integer argument), which is why it isn't named separately.
    @Suppress("ktlint:standard:function-naming")
    private interface ObjectiveCRuntime : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_msgSend(receiver: Pointer, selector: Pointer): Pointer?
        fun objc_msgSend(receiver: Pointer, selector: Pointer, index: Long): Pointer?
    }

    private companion object {
        val isMacOS: Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
        val runtime: ObjectiveCRuntime by lazy { Native.load("objc", ObjectiveCRuntime::class.java) }
    }
}
