package dev.jamkkanman.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** assets의 금지구역·세울 곳 데이터(JSON)를 읽는다. */
object ZoneRepository {

    const val DEFAULT_FILE = "zones_daechi.json"

    fun load(context: Context, fileName: String = DEFAULT_FILE): ZoneData {
        val text = context.assets.open(fileName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parse(text)
    }

    fun parse(text: String): ZoneData {
        val root = JSONObject(text)
        val meta = root.optJSONObject("meta") ?: JSONObject()
        val zones = root.optJSONArray("zones").objects().mapNotNull { o ->
            val type = runCatching { ZoneType.valueOf(o.getString("type")) }.getOrNull() ?: return@mapNotNull null
            Zone(
                id = o.optString("id"),
                type = type,
                name = o.optString("name", type.place),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                source = o.optString("source"),
                radiusM = o.optDouble("radius", type.radiusM),
            )
        }
        val spots = root.optJSONArray("spots").objects().mapNotNull { o ->
            val kind = runCatching { SpotKind.valueOf(o.getString("kind")) }.getOrNull() ?: return@mapNotNull null
            SafeSpot(
                id = o.optString("id"),
                kind = kind,
                name = o.getString("name"),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                note = o.optString("note"),
                source = o.optString("source"),
            )
        }
        val demo = root.optJSONArray("demo").objects().map { o ->
            DemoPlace(o.getString("label"), o.getDouble("lat"), o.getDouble("lon"))
        }
        val sources = meta.optJSONArray("sources").strings()
        return ZoneData(
            region = meta.optString("region", "알 수 없음"),
            isSample = meta.optBoolean("sample", false),
            sources = sources,
            zones = zones,
            spots = spots,
            demo = demo,
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }
}
