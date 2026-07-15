package fr.dayview.app

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/** Repli du corps de la notification de dispersion (isolé pour être testable) : l'intention si
 *  elle est renseignée, sinon [defaultBody]. Les textes eux-mêmes viennent des ressources i18n. */
internal object FocusNudgeCopy {
    fun body(intention: String, defaultBody: String): String = intention.ifBlank { defaultBody }
}

/**
 * Poste une notification macOS « dispersion » depuis le process JVM via le runtime Objective-C.
 *
 * Comme la notification est émise par la JVM elle-même, cliquer dessus ramène DayView au premier
 * plan sans code supplémentaire (comportement macOS par défaut). Miroir de
 * [MacFrontmostApplicationProvider] : appels natifs enveloppés dans runCatching, no-op hors macOS.
 */
internal class MacFocusNudgeNotifier {
    fun notify(title: String, body: String) {
        val notification = buildNotification(title, body) ?: return
        runCatching {
            val centerClass = runtime.objc_getClass("NSUserNotificationCenter")
            val center = message(centerClass, "defaultUserNotificationCenter")
            messageWithObject(center, "deliverNotification:", notification)
        }
    }

    /** Construit la NSUserNotification (titre + corps) ; null hors macOS ou si un appel natif échoue. Exposé pour les tests. */
    internal fun buildNotification(title: String, body: String): Pointer? {
        if (!isMacOS) return null
        return runCatching {
            val notificationClass = runtime.objc_getClass("NSUserNotification")
            val notification = message(message(notificationClass, "alloc"), "init")
            if (notification != null) {
                setString(notification, "setTitle:", title)
                setString(notification, "setInformativeText:", body)
            }
            notification
        }.getOrNull()
    }

    /** Relit une propriété NSString (getter sans argument) en String Kotlin. Exposé pour les tests. */
    internal fun readString(receiver: Pointer, selector: String): String? {
        val value = message(receiver, selector) ?: return null
        val utf8 = message(value, "UTF8String") ?: return null
        return utf8.getString(0, Charsets.UTF_8.name())
    }

    private fun setString(receiver: Pointer, selector: String, value: String) {
        val nsString = makeNSString(value) ?: return
        messageWithObject(receiver, selector, nsString)
    }

    private fun makeNSString(value: String): Pointer? {
        val stringClass = runtime.objc_getClass("NSString") ?: return null
        // stringWithUTF8String: attend un C string UTF-8 NUL-terminé ; JNA ne l'ajoute pas.
        val utf8 = value.toByteArray(Charsets.UTF_8) + 0.toByte()
        return runtime.objc_msgSend(stringClass, runtime.sel_registerName("stringWithUTF8String:"), utf8)
    }

    private fun message(receiver: Pointer?, selector: String): Pointer? {
        if (receiver == null || Pointer.nativeValue(receiver) == 0L) return null
        return runtime.objc_msgSend(receiver, runtime.sel_registerName(selector))
    }

    private fun messageWithObject(receiver: Pointer?, selector: String, arg: Pointer): Pointer? {
        if (receiver == null || Pointer.nativeValue(receiver) == 0L) return null
        return runtime.objc_msgSend(receiver, runtime.sel_registerName(selector), arg)
    }

    // JNA mappe les méthodes vers les symboles natifs par nom ; ces signatures doivent coller au runtime C.
    @Suppress("ktlint:standard:function-naming")
    private interface ObjectiveCRuntime : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_msgSend(receiver: Pointer, selector: Pointer): Pointer?
        fun objc_msgSend(receiver: Pointer, selector: Pointer, arg: Pointer): Pointer?
        fun objc_msgSend(receiver: Pointer, selector: Pointer, arg: ByteArray): Pointer?
    }

    private companion object {
        val isMacOS: Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
        val runtime: ObjectiveCRuntime by lazy { Native.load("objc", ObjectiveCRuntime::class.java) }
    }
}
