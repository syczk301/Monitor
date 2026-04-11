package com.monitor.intelligentflow.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MonitorApiService(
    private val baseUrl: String,
    private val username: String = "",
    private val password: String = ""
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun JSONObject.optNullableString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotEmpty() }
    }

    private fun Request.Builder.withAuth(): Request.Builder {
        if (username.isNotBlank()) {
            header("Authorization", Credentials.basic(username, password))
        }
        return this
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl$path").withAuth().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
    }

    private suspend fun patch(path: String, json: JSONObject): String = withContext(Dispatchers.IO) {
        val body = json.toString().toRequestBody(jsonMediaType)
        val req = Request.Builder().url("$baseUrl$path").withAuth().patch(body).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
    }

    private suspend fun post(path: String, json: JSONObject): String = withContext(Dispatchers.IO) {
        val body = json.toString().toRequestBody(jsonMediaType)
        val req = Request.Builder().url("$baseUrl$path").withAuth().post(body).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
    }

    private suspend fun delete(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl$path").withAuth().delete().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
    }

    suspend fun checkHealth(): Result<String> = runCatching { get("/api/health") }

    suspend fun getStats(): Stats {
        val raw = get("/stats")
        val j = JSONObject(raw)
        return Stats(
            fps = j.optDouble("fps", 0.0),
            avgLatencyMs = j.optDouble("avg_latency_ms", 0.0),
            trackedTargets = j.optInt("tracked_targets", 0),
            gpuUtilization = j.optDouble("gpu_utilization", 0.0),
            captureStatus = j.optString("capture_status", "-"),
            captureBackend = j.optString("capture_backend", "-")
        )
    }

    suspend fun getCaptureInfo(): CaptureInfo {
        val raw = get("/api/capture_info")
        val j = JSONObject(raw)
        return CaptureInfo(
            requestedWidth = j.optInt("requested_width", 3840),
            requestedHeight = j.optInt("requested_height", 2160),
            actualWidth = j.optInt("actual_width", 0),
            actualHeight = j.optInt("actual_height", 0),
            mjpegQuality = j.optInt("mjpeg_quality", 85),
            targetFps = j.optInt("target_fps", 30),
            captureStatus = j.optString("capture_status", "-"),
            captureBackend = j.optString("capture_backend", "-")
        )
    }

    suspend fun applyCameraSettings(width: Int, height: Int, fps: Int): CaptureInfo {
        val raw = post(
            "/api/camera_settings",
            JSONObject()
                .put("width", width)
                .put("height", height)
                .put("fps", fps)
        )
        val j = JSONObject(raw)
        return CaptureInfo(
            requestedWidth = j.optInt("requested_width", width),
            requestedHeight = j.optInt("requested_height", height),
            actualWidth = j.optInt("actual_width", 0),
            actualHeight = j.optInt("actual_height", 0),
            mjpegQuality = j.optInt("mjpeg_quality", 85),
            targetFps = j.optInt("target_fps", fps),
            captureStatus = j.optString("capture_status", "-"),
            captureBackend = j.optString("capture_backend", "-")
        )
    }

    suspend fun getVisits(limit: Int = 200): List<Visit> {
        val raw = get("/api/visits?limit=$limit")
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val j = arr.getJSONObject(i)
            Visit(
                id = j.getInt("id"),
                personId = j.getString("person_id"),
                appearedAt = j.optNullableString("appeared_at"),
                leftAt = j.optNullableString("left_at"),
                staySeconds = j.optDouble("stay_seconds", 0.0),
                note = j.optString("note", ""),
                status = j.optString("status", "")
            )
        }
    }

    suspend fun updateVisitNote(visitId: Int, note: String) {
        patch("/api/visits/$visitId/note", JSONObject().put("note", note))
    }

    suspend fun updatePersonNote(personId: String, note: String) {
        patch("/api/persons/$personId/note", JSONObject().put("note", note))
    }

    suspend fun deleteVisit(visitId: Int) {
        delete("/api/visits/$visitId")
    }

    suspend fun bulkDeleteVisits(ids: List<Int>) {
        val json = JSONObject().put("visit_ids", JSONArray(ids))
        post("/api/visits/bulk-delete", json)
    }

    suspend fun getRois(): List<Roi> {
        val raw = get("/api/rois")
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val j = arr.getJSONObject(i)
            val p = j.optJSONObject("params")
            Roi(
                id = j.getInt("id"),
                bbox = (0 until j.getJSONArray("bbox").length()).map { k -> j.getJSONArray("bbox").getInt(k) },
                params = RoiParams(
                    confidence = p?.optDouble("confidence", 0.18) ?: 0.18,
                    imgsz = p?.optInt("imgsz", 960) ?: 960,
                    upscaleFactor = p?.optDouble("upscale_factor", 3.0) ?: 3.0,
                    enhancedUpscaleFactor = p?.optDouble("enhanced_upscale_factor", 3.5) ?: 3.5
                )
            )
        }
    }

    suspend fun getReport(type: String): Report {
        val raw = get("/api/reports/$type")
        val j = JSONObject(raw)
        val dist = mutableMapOf<Int, Int>()
        val hd = j.optJSONObject("hourly_distribution")
        if (hd != null) {
            hd.keys().forEach { k -> dist[k.toInt()] = hd.getInt(k) }
        }
        return Report(
            reportType = j.optString("report_type", type),
            start = j.optString("start", ""),
            end = j.optString("end", ""),
            totalAppearances = j.optInt("total_appearances", 0),
            uniquePersons = j.optInt("unique_persons", 0),
            peakHour = if (j.isNull("peak_hour")) null else j.optInt("peak_hour"),
            hourlyDistribution = dist
        )
    }

    fun streamUrl(): String = "$baseUrl/stream"
    fun audioUrl(): String = "$baseUrl/api/audio/pcm"
    fun streamAuthHeader(): String? =
        if (username.isNotBlank()) Credentials.basic(username, password) else null
    fun audioAuthHeader(): String? = streamAuthHeader()
}
