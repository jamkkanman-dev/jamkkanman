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
                shape = o.optJSONArray("shape")?.let { arr -> DoubleArray(arr.length()) { arr.getDouble(it) } },
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
        val cctvs = root.optJSONArray("cctv").objects().map { o ->
            Cctv(o.optString("name", "단속 CCTV"), o.getDouble("lat"), o.getDouble("lon"))
        }
        // 단속 기록: {"label": "최근 3개월", "cells": [[위도, 경도, 건수], ...]}
        val enf = root.optJSONObject("enforcement")
        val cells = enf?.optJSONArray("cells")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONArray(i) ?: return@mapNotNull null
                EnforcementCell(c.getDouble(0), c.getDouble(1), c.getInt(2))
            }
        } ?: emptyList()
        // 확인 범위: {"lat0", "lon0", "cellM", "cols", "cells": [칸 번호...]}
        val coverage = root.optJSONObject("coverage")?.let { c ->
            val arr = c.optJSONArray("cells") ?: return@let null
            Coverage(
                lat0 = c.getDouble("lat0"),
                lon0 = c.getDouble("lon0"),
                cellM = c.getDouble("cellM"),
                cols = c.getInt("cols"),
                cells = HashSet<Int>(arr.length() * 2).apply { for (i in 0 until arr.length()) add(arr.getInt(i)) },
            )
        }
        return ZoneData(
            region = meta.optString("region", "알 수 없음"),
            isSample = meta.optBoolean("sample", false),
            sources = sources,
            zones = zones,
            spots = spots,
            demo = demo,
            cctvs = cctvs,
            enforcement = cells,
            enforcementPeriod = enf?.optString("label").orEmpty(),
            coverage = coverage,
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }
}
