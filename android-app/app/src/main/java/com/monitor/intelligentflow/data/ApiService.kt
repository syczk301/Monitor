package com.monitor.intelligentflow.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    private suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).withAuth().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            resp.body?.bytes() ?: ByteArray(0)
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

    suspend fun getSelectedStats(): Stats {
        val raw = get("/api/selected_stats")
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

    private fun parseCamera(j: JSONObject): CameraDevice = CameraDevice(
        id = j.optString("id", ""),
        name = j.optString("name", "摄像头"),
        source = j.optString("source", "local"),
        online = j.optBoolean("online", false),
        streamUrl = j.optString("stream_url", "/stream"),
        audioUrl = j.optString("audio_url", "/api/audio/pcm")
    )

    suspend fun getCameras(): CameraList {
        val j = JSONObject(get("/api/cameras"))
        val arr = j.optJSONArray("cameras") ?: JSONArray()
        return CameraList(
            cameras = (0 until arr.length()).map { parseCamera(arr.getJSONObject(it)) },
            selectedId = j.optString("selected_id", "")
        )
    }

    suspend fun selectCamera(cameraId: String): CameraDevice {
        val j = JSONObject(post("/api/cameras", JSONObject().put("camera_id", cameraId)))
        val selected = j.optJSONObject("selected")
            ?: throw Exception("后端未返回已选择的摄像头")
        return parseCamera(selected)
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

    suspend fun getRecordings(): List<RecordingGroup> {
        val raw = get("/api/recordings")
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val group = arr.getJSONObject(i)
            val items = group.getJSONArray("items")
            RecordingGroup(
                day = group.optString("day", ""),
                items = (0 until items.length()).map { j ->
                    val item = items.getJSONObject(j)
                    RecordingFile(
                        relativePath = item.optString("relative_path", ""),
                        filename = item.optString("filename", ""),
                        startedAt = item.optString("started_at", ""),
                        sizeBytes = item.optLong("size_bytes", 0L),
                        modifiedAt = item.optString("modified_at", ""),
                        deviceId = item.optString("device_id", "local"),
                        deviceName = item.optString("device_name", "当前设备")
                    )
                }
            )
        }
    }

    private fun parseRecordingStatus(raw: String): RecordingStatus {
        val j = JSONObject(raw)
        val s = j.optJSONObject("schedule")
        val days = mutableListOf<Int>()
        val arr = s?.optJSONArray("days")
        if (arr != null) {
            for (i in 0 until arr.length()) days.add(arr.getInt(i))
        }
        return RecordingStatus(
            mode = j.optString("mode", "off"),
            schedule = RecordingSchedule(
                start = s?.optString("start", "00:00") ?: "00:00",
                end = s?.optString("end", "23:59") ?: "23:59",
                days = days
            ),
            scheduleInWindow = j.optBoolean("schedule_in_window", false),
            recordingActive = j.optBoolean("recording_active", false),
            recordingStatus = j.optString("recording_status", "-"),
            recordingDeviceId = j.optString("recording_device_id", "local"),
            recordingDeviceName = j.optString("recording_device_name", "当前设备")
        )
    }

    suspend fun getRecordingStatus(): RecordingStatus =
        parseRecordingStatus(get("/api/recording_schedule"))

    suspend fun getSelectedRecordingStatus(): RecordingStatus =
        parseRecordingStatus(get("/api/selected_recording_status"))

    suspend fun setRecordingMode(mode: String) {
        post("/api/recording_mode", JSONObject().put("mode", mode))
    }

    suspend fun setRecordingSchedule(schedule: RecordingSchedule): RecordingStatus {
        val raw = post(
            "/api/recording_schedule",
            JSONObject()
                .put("start", schedule.start)
                .put("end", schedule.end)
                .put("days", JSONArray(schedule.days))
        )
        return parseRecordingStatus(raw)
    }

    suspend fun downloadRecording(relativePath: String, cacheDir: File): File {
        val recordingsDir = File(cacheDir, "recordings").apply { mkdirs() }
        val target = File(recordingsDir, relativePath.substringAfterLast('/'))
        val url = recordingUrl(relativePath)
        val bytes = getBytes(url)
        target.writeBytes(bytes)
        return target
    }

    private fun endpointUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    }

    fun streamUrl(camera: CameraDevice? = null): String =
        endpointUrl(camera?.streamUrl ?: "/stream")

    fun audioUrl(camera: CameraDevice? = null): String =
        endpointUrl(camera?.audioUrl ?: "/api/audio/pcm")
    fun recordingUrl(relativePath: String): String =
        baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/recordings/file")
            .addQueryParameter("path", relativePath)
            .build()
            .toString()
    fun streamAuthHeader(): String? =
        if (username.isNotBlank()) Credentials.basic(username, password) else null
    fun audioAuthHeader(): String? = streamAuthHeader()
}
