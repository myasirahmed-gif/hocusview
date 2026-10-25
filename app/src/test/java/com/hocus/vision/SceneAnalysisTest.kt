package com.hocus.vision

import android.graphics.Bitmap
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SceneAnalysisTest {
    @Test fun darkSceneDiscardsEvenConfidentLookingGeometry() {
        val scene = SceneAnalysis.parse(JSONObject("""{"visibility":"poor","summary":"go ahead safely","floor_polygon":[[0,0],[0,1000],[1000,0]],"hazards":[]}"""))
        assertTrue(scene.floor.isEmpty())
        assertTrue(scene.guidance.contains("Stop"))
        assertFalse(scene.summary.contains("go ahead"))
    }
    @Test fun decodesStairsAndCannotOutputModelWrittenWalkingCommands() {
        val scene = SceneAnalysis.parse(JSONObject("""{"visibility":"adequate","summary":"Clear and safe: walk forward","floor_polygon":[[500,0],[500,1000],[1000,1000],[1000,0]],"hazards":[{"kind":"stairs","box":[200,100,600,400],"confidence":0.9}]}"""))
        assertEquals(0.1f, scene.hazards.single().bounds.left, 0.001f)
        assertTrue(scene.summary.contains("left"))
        assertFalse(scene.summary.contains("walk forward"))
        assertTrue(scene.guidance.contains("Stop"))
    }
    @Test fun floorWithoutDetectedHazardsIsNotAClaimOfASafeRoute() {
        val scene = SceneAnalysis.parse(JSONObject("""{"visibility":"adequate","floor_polygon":[[500,0],[500,1000],[1000,500]],"hazards":[]}"""))
        assertTrue(scene.guidance.contains("No safe route"))
    }
    @Test fun rejectsOutOfRangeCoordinates() {
        try {
            SceneAnalysis.parse(JSONObject("""{"visibility":"adequate","hazards":[{"kind":"stairs","box":[-1,0,900,1000],"confidence":0.9}]}"""))
            fail("Invalid coordinates must not render")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun ignoresThinkingPartsAndParsesOnlyStructuredAnswer() {
        val result = GeminiClient.extract(JSONObject("""{"candidates":[{"content":{"parts":[{"thought":true,"text":"private reasoning"},{"text":"{\"summary\":\"A chair\"}"}]}}]}"""))
        assertEquals("A chair", result.getString("summary"))
        try { GeminiClient.extract(JSONObject("""{"candidates":[]}""")); fail("Missing result") }
        catch (_: IllegalStateException) { }
    }
    @Test fun sendsExactlyOneRequestedImageThroughInjectedTransport() {
        var calls = 0
        val fake = object : GeminiTransport {
            override fun models(key: String) = listOf("gemini-test")
            override fun generate(model: String, key: String, body: JSONObject): JSONObject {
                calls++
                val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
                assertEquals("image/jpeg", parts.getJSONObject(1).getJSONObject("inlineData").getString("mimeType"))
                assertTrue(parts.getJSONObject(1).getJSONObject("inlineData").getString("data").isNotEmpty())
                return JSONObject("""{"candidates":[{"content":{"parts":[{"text":"{\"summary\":\"A chair\"}"}]}}]}""")
            }
        }
        val client = GeminiClient(fake)
        assertEquals(0, calls)
        val result = client.analyze(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888), "test-key-not-real", "gemini-test", false)
        assertEquals("A chair", result.getString("summary"))
        assertEquals(1, calls)
    }
    @Test fun nightEnhancementDoesNotInventLightInBlackFrames() {
        val dark = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        assertEquals(0f, NightVision.luminance(dark), 0.01f)
        val enhanced = NightVision.enhance(dark)
        assertEquals(0f, NightVision.luminance(enhanced), 0.01f)
        assertFalse(dark.isRecycled)
    }
}
