package fr.dayview.app

/** Dock affordances for a pending drift reminder: a badge, and a single attention bounce. */
interface DockAttentionProvider {
    fun setBadge(visible: Boolean)
    fun bounceOnce()
}

object NoopDockAttentionProvider : DockAttentionProvider {
    override fun setBadge(visible: Boolean) = Unit
    override fun bounceOnce() = Unit
}
