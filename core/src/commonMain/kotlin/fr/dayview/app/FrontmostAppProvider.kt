package fr.dayview.app

/** Native source of the frontmost application and the running-app list (for on-goal config). */
interface FrontmostAppProvider {
    fun frontmostBundleId(): String?
    fun runningApps(): List<AppRef>
}

object NoopFrontmostAppProvider : FrontmostAppProvider {
    override fun frontmostBundleId(): String? = null
    override fun runningApps(): List<AppRef> = emptyList()
}
