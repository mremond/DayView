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

    @Test
    fun addCountsAlreadyUsedSlotsTowardTheCap() {
        // 1 active + 2 already-used (completed) = 3 → at cap, add is a no-op
        assertEquals(listOf("a"), addPlannedObligation(listOf("a"), "b", alreadyUsed = 2))
        // 1 active + 1 already-used = 2 → one slot free, add succeeds
        assertEquals(listOf("a", "b"), addPlannedObligation(listOf("a"), "b", alreadyUsed = 1))
    }

    @Test
    fun addDefaultsToNoAlreadyUsedSlots() {
        assertEquals(listOf("a", "b"), addPlannedObligation(listOf("a"), "b"))
    }

    @Test
    fun markCompletedMovesMotifAndSanitizes() {
        val (active, completed) = markObligationCompleted(listOf("Appel", "Facture"), emptyList(), "  appel ")
        assertEquals(listOf("Facture"), active)
        assertEquals(listOf("Appel"), completed)
    }

    @Test
    fun markCompletedIsANoOpForBlankOrMissingMotif() {
        assertEquals(listOf("a") to emptyList<String>(), markObligationCompleted(listOf("a"), emptyList(), "  \n"))
        assertEquals(listOf("a") to listOf("x"), markObligationCompleted(listOf("a"), listOf("x"), "absent"))
    }

    @Test
    fun editRenamesInPlaceAndPreservesOrder() {
        assertEquals(
            listOf("a", "B2", "c"),
            editPlannedObligation(listOf("a", "b", "c"), emptyList(), oldMotif = "b", newLabel = "B2"),
        )
    }

    @Test
    fun editSanitizesTheNewLabel() {
        assertEquals(
            listOf("a", "New label", "c"),
            editPlannedObligation(listOf("a", "b", "c"), emptyList(), oldMotif = "b", newLabel = "  New\nlabel "),
        )
    }

    @Test
    fun editMatchesOldMotifCaseInsensitively() {
        assertEquals(
            listOf("Facture"),
            editPlannedObligation(listOf("Appel"), emptyList(), oldMotif = "  appel ", newLabel = "Facture"),
        )
    }

    @Test
    fun editAllowsCasingOnlyChange() {
        assertEquals(
            listOf("Appel"),
            editPlannedObligation(listOf("appel"), emptyList(), oldMotif = "appel", newLabel = "Appel"),
        )
    }

    @Test
    fun editRejectsBlankLabel() {
        assertEquals(null, editPlannedObligation(listOf("a", "b"), emptyList(), oldMotif = "b", newLabel = "  \n"))
    }

    @Test
    fun editRejectsUnchangedLabel() {
        assertEquals(null, editPlannedObligation(listOf("a", "b"), emptyList(), oldMotif = "b", newLabel = "b"))
    }

    @Test
    fun editRejectsDuplicateOfAnotherActiveItem() {
        assertEquals(null, editPlannedObligation(listOf("Appel", "Facture"), emptyList(), oldMotif = "Facture", newLabel = "appel"))
    }

    @Test
    fun editRejectsDuplicateOfACompletedItem() {
        assertEquals(null, editPlannedObligation(listOf("Appel"), listOf("Facture"), oldMotif = "Appel", newLabel = "facture"))
    }

    @Test
    fun editRejectsWhenOldMotifIsAbsent() {
        assertEquals(null, editPlannedObligation(listOf("a"), emptyList(), oldMotif = "zzz", newLabel = "x"))
    }
}
