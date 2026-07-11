package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class OnGoalAppsTest {
    @Test
    fun encodeThenDecodeRoundTripsAppRefs() {
        val apps = setOf(
            AppRef("com.processone.draftline", "Draftline"),
            AppRef("com.apple.dt.Xcode", "Xcode"),
        )
        assertEquals(apps, decodeAppRefs(encodeAppRefs(apps)))
    }

    @Test
    fun decodeSkipsBlankAndMalformedLines() {
        val encoded = "com.processone.draftline\tDraftline\n\n\tOrphan\nnotabs"
        assertEquals(setOf(AppRef("com.processone.draftline", "Draftline")), decodeAppRefs(encoded))
    }

    @Test
    fun decodeOfEmptyStringIsEmptySet() {
        assertEquals(emptySet(), decodeAppRefs(""))
    }
}
