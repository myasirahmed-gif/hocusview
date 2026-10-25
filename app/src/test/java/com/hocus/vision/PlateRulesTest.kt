package com.hocus.vision

import org.junit.Assert.*
import org.junit.Test

class PlateRulesTest {
    private fun line(text: String) = PlateLine(text, Box(70f, 140f, 230f, 170f))
    @Test fun acceptsMixedPlateAndPreservesCharacters() {
        val plate = PlateRules.best(listOf(line("ABC-1234")), 300, 200)!!
        assertEquals("ABC1234", plate.canonical)
        assertEquals("ABC-1234", plate.text)
    }
    @Test fun supportsNumericPlatesWithoutGuessingCharacters() {
        assertEquals("123456", PlateRules.best(listOf(line("123456")), 300, 200)?.canonical)
        assertEquals("ABO0123", PlateRules.best(listOf(line("ABO 0123")), 300, 200)?.canonical)
    }
    @Test fun rejectsBrandPhoneAndAdvertisementText() {
        for (text in listOf("HONDA", "03123456789", "MODEL 2024", "TEL 123456", "CALL 12345"))
            assertNull(text, PlateRules.best(listOf(line(text)), 300, 200))
    }
    @Test fun combinesTwoLineRegionalPlate() {
        val prefix = PlateLine("LEA-23", Box(80f, 115f, 210f, 137f))
        val number = PlateLine("1234", Box(90f, 141f, 210f, 163f))
        assertEquals("LEA231234", PlateRules.best(listOf(prefix, number), 300, 200)?.canonical)
    }
    @Test fun rejectsTinyTextAndTextAboveVehiclePlateRegion() {
        assertNull(PlateRules.best(listOf(PlateLine("ABC1234", Box(10f, 3f, 110f, 8f))), 300, 200))
        assertNull(PlateRules.best(listOf(PlateLine("ABC1234", Box(30f, 5f, 160f, 25f))), 300, 200))
    }
    @Test fun requiresTwoSeparatedReadsForSameVehicle() {
        val consensus = PlateConsensus()
        assertFalse(consensus.observe(1, "ABC1234", 1000))
        assertFalse(consensus.observe(1, "ABC1234", 1100))
        assertTrue(consensus.observe(1, "ABC1234", 1900))
        assertFalse(consensus.observe(2, "ABC1234", 2000))
        assertFalse(consensus.observe(1, "XYZ9876", 2900))
        assertFalse(consensus.observe(1, null, 3100))
    }
    @Test fun staleConsensusDoesNotConfirmANewObservation() {
        val consensus = PlateConsensus()
        consensus.observe(1, "ABC1234", 1000)
        assertFalse(consensus.observe(1, "ABC1234", 9000))
    }
}
