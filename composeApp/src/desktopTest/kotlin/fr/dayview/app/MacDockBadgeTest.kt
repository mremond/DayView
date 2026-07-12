package fr.dayview.app

import kotlin.test.Test

class MacDockBadgeTest {
    @Test
    fun badgeCanBeShownAndClearedWithoutError() {
        MacDockBadge().use { badge ->
            badge.update(true)
            badge.update(true)
            badge.update(false)
        }
    }
}
