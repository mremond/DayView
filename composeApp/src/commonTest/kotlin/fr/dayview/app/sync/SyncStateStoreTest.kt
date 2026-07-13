package fr.dayview.app.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncStateStoreTest {
    private var backing: String? = null
    private fun store() = FileSyncStatePersistence(read = { backing }, write = { backing = it })

    @Test
    fun loadsDefaultWhenEmpty() = runTest {
        val state = store().load()
        assertNull(state.baseRevision)
        assertNull(state.baseDocument)
    }

    @Test
    fun roundTripsRevisionAndDocument() = runTest {
        val s = store()
        s.save(SyncState("r5", sampleDocument()))
        val loaded = store().load()
        assertEquals("r5", loaded.baseRevision)
        assertEquals(sampleDocument(), loaded.baseDocument)
    }
}
