package fr.dayview.app

/** Textes de la notification de dispersion (logique de repli isolée pour être testable). */
internal object FocusNudgeCopy {
    const val TITLE = "Reviens à l'essentiel"
    const val DEFAULT_BODY = "Une seule chose à la fois."

    fun body(intention: String): String = intention.ifBlank { DEFAULT_BODY }
}
