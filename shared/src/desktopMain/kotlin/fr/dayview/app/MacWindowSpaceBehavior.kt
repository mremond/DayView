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
    private const val FULL_SCREEN_PRIMARY = 1L shl 7
    private const val FULL_SCREEN_AUXILIARY = 1L shl 8
    private const val FULL_SCREEN_NONE = 1L shl 9
    private const val PRIMARY = 1L shl 16
    private const val AUXILIARY = 1L shl 17
    private const val CAN_JOIN_ALL_APPLICATIONS = 1L shl 18
    private const val ACCESSORY_APPLICATION = 1L
    private const val REGULAR_APPLICATION = 0L
    private const val SCREEN_SAVER_WINDOW_LEVEL = 1_000L

    internal fun withFullScreenVisibility(existing: Long): Long {
        val incompatible =
            MOVE_TO_ACTIVE_SPACE or FULL_SCREEN_PRIMARY or FULL_SCREEN_NONE or PRIMARY or AUXILIARY
        return (existing and incompatible.inv()) or
            CAN_JOIN_ALL_SPACES or FULL_SCREEN_AUXILIARY or CAN_JOIN_ALL_APPLICATIONS
    }

    /** Returns false while the native peer has not appeared yet, so callers can retry. */
    fun enableForWindowTitle(title: String): Boolean {
        if (!isMacOS) return true
        return runCatching {
            val application = message(runtime.objc_getClass("NSApplication"), "sharedApplication") ?: return false
            messageLongArgument(application, "setActivationPolicy:", ACCESSORY_APPLICATION)
            val windows = message(application, "windows") ?: return false
            val count = messageLong(windows, "count")
            var configured = false
            for (index in 0 until count) {
                val window = messageLongArgument(windows, "objectAtIndex:", index) ?: continue
                if (readString(window, "title") != title) continue
                // Compose/AWT may retain a hidden native peer with the same title. It still
                // participates in Space placement, so give every matching peer the same
                // behavior. Never order one front: that is what exposed the empty ghost.
                val existing = messageLong(window, "collectionBehavior")
                messageLongArgument(window, "setCollectionBehavior:", withFullScreenVisibility(existing))
                messageLongArgument(window, "setLevel:", SCREEN_SAVER_WINDOW_LEVEL)
                messageLongArgument(window, "setHidesOnDeactivate:", 0L)
                configured = true
            }
            configured
        }.getOrDefault(false)
    }

    fun restoreRegularApplicationMode() {
        if (!isMacOS) return
        runCatching {
            val application = message(runtime.objc_getClass("NSApplication"), "sharedApplication") ?: return
            messageLongArgument(application, "setActivationPolicy:", REGULAR_APPLICATION)
        }
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
