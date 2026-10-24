package com.hocus.vision

import org.junit.Assert.*
import org.junit.Test

class MotionTrackerTest {
    private fun detection(x: Float, appearance: FloatArray = floatArrayOf(1f, 0f), label: String = "bottle") =
        Observation(Box(x, 20f, x + 40f, 80f), label, 0.8f, appearance)

    @Test fun fastMotionKeepsIdWithoutBoxOverlap() {
        val tracker = MotionTracker()
        val id = tracker.update(listOf(detection(0f)), 1000, null).single().id
        assertEquals(id, tracker.update(listOf(detection(45f)), 1100, id).single().id)
    }
    @Test fun shortOcclusionRetainsSelectedTrackAndRecovers() {
        val tracker = MotionTracker()
        val id = tracker.update(listOf(detection(0f)), 1000, null).single().id
        val predicted = tracker.update(emptyList(), 1800, id).single()
        assertTrue(predicted.predicted)
        assertEquals(id, tracker.update(listOf(detection(5f)), 1900, id).single().id)
        assertTrue(tracker.update(emptyList(), 3900, id).isEmpty())
    }
    @Test fun associationIsOneToOne() {
        val tracker = MotionTracker()
        tracker.update(listOf(detection(0f), detection(70f)), 1000, null)
        val result = tracker.update(listOf(detection(30f)), 1100, null)
        assertEquals(1, result.count { !it.predicted })
        assertEquals(2, result.map { it.id }.distinct().size)
    }
    @Test fun appearanceHelpsSameClassObjectsCross() {
        val tracker = MotionTracker()
        val first = tracker.update(listOf(detection(0f), detection(60f, floatArrayOf(0f, 1f))), 1000, null)
        val second = tracker.update(listOf(detection(45f), detection(15f, floatArrayOf(0f, 1f))), 1100, null)
        assertTrue(second.first { it.id == first[0].id }.box.cx > second.first { it.id == first[1].id }.box.cx)
    }
    @Test fun confirmedLabelSurvivesUnknownFallback() {
        val tracker = MotionTracker()
        val id = tracker.update(listOf(detection(0f)), 1000, null).single().id
        assertEquals("bottle", tracker.update(listOf(detection(2f, label = "Unknown object")), 1100, id).single().label)
    }
    @Test fun expiredTrackDoesNotReuseId() {
        val tracker = MotionTracker()
        val first = tracker.update(listOf(detection(0f)), 1000, null).single().id
        assertNotEquals(first, tracker.update(listOf(detection(0f)), 3000, null).single().id)
    }
    @Test fun selectedPatchCannotBeStolenByOverlappingSceneDetection() {
        val tracker = MotionTracker()
        val table = Observation(Box(0f, 0f, 400f, 400f), "dining table", 0.9f)
        tracker.update(listOf(table), 1000, null)
        val patch = Observation(Box(90f, 100f, 150f, 160f), "Unknown object", 0.9f)
        val selected = tracker.seed(patch, 1100)
        val result = tracker.update(listOf(table, patch.copy(preferredId = selected)), 1200, selected)
        assertEquals(2, result.size)
        assertFalse(result.first { it.id == selected }.predicted)
        assertEquals(120f, result.first { it.id == selected }.box.cx, 0.01f)
    }
}
