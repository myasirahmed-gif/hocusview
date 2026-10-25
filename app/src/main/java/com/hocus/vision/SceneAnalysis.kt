package com.hocus.vision

import org.json.JSONObject

data class ScenePoint(val x: Float, val y: Float)
data class SceneHazard(val kind: String, val label: String, val bounds: Box, val confidence: Float)
data class SceneAnalysis(val visibility: String, val summary: String, val floor: List<ScenePoint>, val hazards: List<SceneHazard>) {
    val guidance: String get() = when {
        visibility != "adequate" -> "Visibility is uncertain. Stop and use a light; no route established."
        hazards.any { it.kind == "stairs" || it.kind == "step" || it.kind == "drop" } -> "Possible stairs, step or drop observed. Stop and check directly with a light."
        hazards.isNotEmpty() -> "Possible obstacles observed. Check your surroundings; this snapshot does not establish a safe route."
        else -> "Snapshot observations only. No safe route or obstacle-free path has been established."
    }
    companion object {
        fun parse(value: JSONObject): SceneAnalysis {
            val visibility = value.optString("visibility").takeIf { it in setOf("adequate", "poor", "unknown") } ?: "unknown"
            if (visibility != "adequate") return SceneAnalysis(visibility, "Insufficient visible detail for a floor or stair assessment.", emptyList(), emptyList())
            fun coordinate(number: Double): Float {
                require(number.isFinite() && number in 0.0..1000.0) { "Invalid scene coordinates." }
                return (number / 1000).toFloat()
            }
            val polygon = value.optJSONArray("floor_polygon")
            val floor = if (polygon != null && polygon.length() in 3..10) (0 until polygon.length()).map {
                val point = polygon.getJSONArray(it); require(point.length() == 2)
                ScenePoint(coordinate(point.getDouble(1)), coordinate(point.getDouble(0)))
            } else emptyList()
            val items = value.optJSONArray("hazards")
            val hazards = if (items == null) emptyList() else (0 until minOf(items.length(), 8)).mapNotNull {
                val hazard = items.getJSONObject(it)
                val box = hazard.getJSONArray("box"); require(box.length() == 4)
                val bounds = Box(coordinate(box.getDouble(1)), coordinate(box.getDouble(0)), coordinate(box.getDouble(3)), coordinate(box.getDouble(2)))
                require(bounds.right > bounds.left && bounds.bottom > bounds.top)
                val confidence = hazard.optDouble("confidence", 0.0)
                require(confidence.isFinite() && confidence in 0.0..1.0)
                if (confidence < 0.5) null else SceneHazard(hazard.optString("kind").takeIf { kind -> kind in setOf("stairs", "step", "drop", "obstacle") } ?: "unknown",
                    hazard.optString("label", "Possible obstacle").take(50), bounds, confidence.toFloat())
            }
            // Do not surface model-written walking instructions, even if the model ignores its prompt.
            val summary = (if (floor.isEmpty()) "No floor region established. " else "A floor-like region was outlined. ") +
                hazards.joinToString(" ") { hazard ->
                    val sector = when { hazard.bounds.cx < 0.33f -> "left"; hazard.bounds.cx > 0.67f -> "right"; else -> "center" }
                    "Possible ${hazard.kind} in the $sector of the snapshot."
                }
            return SceneAnalysis(visibility, summary, floor, hazards)
        }
    }
}
