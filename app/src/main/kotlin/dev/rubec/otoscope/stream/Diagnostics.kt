package dev.rubec.otoscope.stream

import org.json.JSONObject

/**
 * Helpers for building the per-session [CameraSession.diagnostics] map.
 *
 * Each vendor builds its own map however it likes — curated key formatting,
 * value combinations, units — so we don't try to abstract the whole thing.
 * What IS shared is the "turn a telemetry JSON blob into a tidy ordered map"
 * step, since vendors that just want to surface whatever the firmware reports
 * (like Wudaopu) shouldn't have to reimplement the JSON walk.
 */
internal object Diagnostics {

    /**
     * Convert a JSON object into a `LinkedHashMap<String, String>` suitable for
     * [CameraSession.diagnostics]. Empty / `null` string values are dropped,
     * keys in [exclude] are skipped, and snake_case keys are rendered as
     * Title Case for the overlay.
     */
    fun fromJson(obj: JSONObject, exclude: Set<String> = emptySet()): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val it = obj.keys()
        while (it.hasNext()) {
            val key = it.next()
            if (key in exclude) continue
            val value = obj.opt(key)?.toString()?.takeIf { it.isNotEmpty() && it != "null" }
                ?: continue
            out[prettyKey(key)] = value
        }
        return out
    }

    private fun prettyKey(snake: String): String =
        snake.split('_').joinToString(" ") { word ->
            word.replaceFirstChar { c -> c.uppercaseChar() }
        }
}
