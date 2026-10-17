package dev.muhib.s23optimizer.data

import dev.muhib.s23optimizer.core.*
import org.json.JSONArray
import org.json.JSONObject

/** Schema v1. Explicit nulls preserve unknown measurements across process death. */
object SnapshotCodec {
    private fun JSONObject.putNullable(key: String, value: Any?) = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.doubleOrNull(key: String): Double? = if (isNull(key)) null else optDouble(key).takeIf { it.isFinite() }
    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun strings(values: List<String>) = JSONArray().apply { values.forEach { put(it) } }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
    fun encode(snapshot: AuditSnapshot): String = JSONObject().apply {
        put("version", 1)
        put("notes", strings(snapshot.notes))
        put("metrics", JSONArray().apply { snapshot.metrics.forEach { m -> put(JSONObject().apply {
            put("id", m.id); put("label", m.label); putNullable("value", m.value); put("unit", m.unit)
            put("source", m.source); put("note", m.note); put("cumulative", m.cumulative); putNullable("display", m.display)
        }) } })
        put("apps", JSONArray().apply { snapshot.apps.forEach { a -> put(JSONObject().apply {
            put("package", a.packageName); put("label", a.label); put("uid", a.uid); put("system", a.system); put("enabled", a.enabled)
            put("installed", a.firstInstallMs); putNullable("lastUsed", a.lastUsedMs); putNullable("foreground", a.foregroundMs)
            put("protected", strings(a.protectedReasons)); putNullable("mah", a.estimatedMah); putNullable("wake", a.partialWakeMs)
            putNullable("alarms", a.wakeupAlarms); putNullable("bucket", a.standbyBucket); putNullable("startup", a.startupCandidate)
            put("standbySource", a.standbySource); put("drainSource", a.drainSource)
        }) } })
    }.toString()
    fun decode(row: SnapshotEntity): AuditSnapshot {
        val json = JSONObject(row.payload)
        val metrics = json.getJSONArray("metrics").let { array -> (0 until array.length()).map { i -> array.getJSONObject(i).let { o ->
            Metric(o.getString("id"), o.getString("label"), o.doubleOrNull("value"), o.getString("unit"), o.getString("source"), o.optString("note"), o.optBoolean("cumulative"), if (o.isNull("display")) null else o.getString("display"))
        } } }
        val apps = json.getJSONArray("apps").let { array -> (0 until array.length()).map { i -> array.getJSONObject(i).let { o ->
            AppProfile(o.getString("package"), o.getString("label"), o.getInt("uid"), o.getBoolean("system"), o.getBoolean("enabled"), o.getLong("installed"), o.longOrNull("lastUsed"), o.longOrNull("foreground"),
                o.getJSONArray("protected").strings(), o.doubleOrNull("mah"), o.longOrNull("wake"), o.longOrNull("alarms"), o.longOrNull("bucket")?.toInt(),
                if (o.isNull("startup")) null else o.getBoolean("startup"), o.optString("standbySource", "unavailable"), o.optString("drainSource", "unavailable"))
        } } }
        return AuditSnapshot(row.id, row.takenAt, row.role, metrics, apps, json.getJSONArray("notes").strings())
    }
}
