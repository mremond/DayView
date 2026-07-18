package fr.dayview.app

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Makes the Compose/JVM mini window accompany full-screen applications on macOS.
 *
 * AWT exposes always-on-top but not NSWindowCollectionBehavior. Looking up the
 * already-created NSWindow by title avoids depending on JDK-internal AWT peer APIs.
 */
internal object MacWindowSpaceBehavior {
    private const val CAN_JOIN_ALL_SPACES = 1L shl 0
    private const val MOVE_TO_ACTIVE_SPACE = 1L shl 1
    private const val FULL_SCREEN_AUXILIARY = 1L shl 8

    internal fun withFullScreenVisibility(existing: Long): Long = (existing and MOVE_TO_ACTIVE_SPACE.inv()) or
        CAN_JOIN_ALL_SPACES or FULL_SCREEN_AUXILIARY

    /** Returns false while the native peer has not appeared yet, so callers can retry. */
    fun enableForWindowTitle(title: String): Boolean {
        if (!isMacOS) return true
        return runCatching {
            val application = message(runtime.objc_getClass("NSApplication"), "sharedApplication") ?: return false
            val windows = message(application, "windows") ?: return false
            val count = messageLong(windows, "count")
            for (index in 0 until count) {
                val window = messageLongArgument(windows, "objectAtIndex:", index) ?: continue
                if (readString(window, "title") != title) continue
                val existing = messageLong(window, "collectionBehavior")
                messageLongArgument(window, "setCollectionBehavior:", withFullScreenVisibility(existing))
                return true
            }
            false
        }.getOrDefault(false)
    }

    private fun readString(receiver: Pointer, selector: String): String? {
        val value = message(receiver, selector) ?: return null
        val utf8 = message(value, "UTF8String") ?: return null
        return utf8.getString(0, Charsets.UTF_8.name())
    }

    private fun message(receiver: Pointer?, selector: String): Pointer? {
        if (receiver == null || Pointer.nativeValue(receiver) == 0L) return null
        return runtime.objc_msgSend(receiver, runtime.sel_registerName(selector))
    }

    private fun messageLong(receiver: Pointer?, selector: String): Long {
        if (receiver == null || Pointer.nativeValue(receiver) == 0L) return 0L
        return runtime.objc_msgSend(receiver, runtime.sel_registerName(selector))
            ?.let(Pointer::nativeValue)
            ?: 0L
    }

    private fun messageLongArgument(receiver: Pointer?, selector: String, argument: Long): Pointer? {
        if (receiver == null || Pointer.nativeValue(receiver) == 0L) return null
        return runtime.objc_msgSend(receiver, runtime.sel_registerName(selector), argument)
    }

    @Suppress("ktlint:standard:function-naming")
    private interface ObjectiveCRuntime : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_msgSend(receiver: Pointer, selector: Pointer): Pointer?
        fun objc_msgSend(receiver: Pointer, selector: Pointer, argument: Long): Pointer?
    }

    private val isMacOS: Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    private val runtime: ObjectiveCRuntime by lazy { Native.load("objc", ObjectiveCRuntime::class.java) }
}
