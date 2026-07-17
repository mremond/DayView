package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class MacDockBouncerTest {
    @Test
    fun bouncesOnceForANewReminder() {
        var bounces = 0
        val bouncer = MacDockBouncer(requestAttention = { bounces++ })

        val reminder = Instant.fromEpochMilliseconds(1_000)
        bouncer.update(reminder)
        bouncer.update(reminder)

        assertEquals(1, bounces)
    }

    @Test
    fun doesNotBounceWithoutAReminder() {
        var bounces = 0
        val bouncer = MacDockBouncer(requestAttention = { bounces++ })

        bouncer.update(null)

        assertEquals(0, bounces)
    }

    @Test
    fun bouncesAgainForEachNewReminder() {
        var bounces = 0
        val bouncer = MacDockBouncer(requestAttention = { bounces++ })

        bouncer.update(Instant.fromEpochMilliseconds(1_000))
        bouncer.update(null)
        bouncer.update(Instant.fromEpochMilliseconds(2_000))

        assertEquals(2, bounces)
    }

    @Test
    fun defaultAttentionRequestDoesNotError() {
        MacDockBouncer().update(Instant.fromEpochMilliseconds(1_000))
    }
}
