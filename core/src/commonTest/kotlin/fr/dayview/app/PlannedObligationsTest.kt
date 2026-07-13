package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class PlannedObligationsTest {
    @Test
    fun addSanitizesAppendsAndPreservesOrder() {
        val one = addPlannedObligation(emptyList(), "  Appel\nclient ")
        assertEquals(listOf("Appel client"), one)
        assertEquals(listOf("Appel client", "Facture"), addPlannedObligation(one, "Facture"))
    }

    @Test
    fun addIgnoresBlankMotifs() {
        assertEquals(listOf("Facture"), addPlannedObligation(listOf("Facture"), "  \n"))
    }

    @Test
    fun addIsANoOpAtTheCap() {
        val full = listOf("a", "b", "c")
        assertEquals(MAX_PLANNED_OBLIGATIONS, full.size)
        assertEquals(full, addPlannedObligation(full, "d"))
    }

    @Test
    fun removeDropsMotifCaseInsensitivelyAndNoOpsOnBlankOrMissing() {
        assertEquals(listOf("Facture"), removePlannedObligation(listOf("Facture", "Appel"), "  appel "))
        assertEquals(listOf("Facture"), removePlannedObligation(listOf("Facture"), "absent"))
        assertEquals(listOf("Facture"), removePlannedObligation(listOf("Facture"), "  \n"))
    }

    @Test
    fun encodeDecodeRoundTripsAndCapsOnDecode() {
        val obligations = listOf("Appel client", "Facture")
        assertEquals(obligations, decodePlannedObligations(encodePlannedObligations(obligations)))
        assertEquals(emptyList(), decodePlannedObligations(""))
        assertEquals(3, decodePlannedObligations("a\nb\nc\nd\ne").size)
    }
}
