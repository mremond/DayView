package fr.dayview.app

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

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
