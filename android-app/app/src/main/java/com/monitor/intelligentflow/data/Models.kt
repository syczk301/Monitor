package com.monitor.intelligentflow.data

data class Stats(
    val fps: Double = 0.0,
    val avgLatencyMs: Double = 0.0,
    val trackedTargets: Int = 0,
    val gpuUtilization: Double = 0.0,
    val captureStatus: String = "-",
    val captureBackend: String = "-"
)

data class CaptureInfo(
    val requestedWidth: Int = 3840,
    val requestedHeight: Int = 2160,
    val actualWidth: Int = 0,
    val actualHeight: Int = 0,
    val mjpegQuality: Int = 85,
    val targetFps: Int = 30,
    val captureStatus: String = "-",
    val captureBackend: String = "-"
)

data class CameraDevice(
    val id: String,
    val name: String,
    val source: String,
    val online: Boolean,
    val streamUrl: String,
    val audioUrl: String
)

data class CameraList(
    val cameras: List<CameraDevice> = emptyList(),
    val selectedId: String = ""
)

data class Visit(
    val id: Int,
    val personId: String,
    val appearedAt: String?,
    val leftAt: String?,
    val staySeconds: Double,
    val note: String,
    val status: String
)

data class PersonGroup(
    val personId: String,
    val visits: List<Visit>,
    val totalStay: Double,
    val hasActive: Boolean,
    val latestNote: String,
    val firstSeen: String?,
    val lastSeen: String?
)

data class RoiParams(
    val confidence: Double = 0.18,
    val imgsz: Int = 960,
    val upscaleFactor: Double = 3.0,
    val enhancedUpscaleFactor: Double = 3.5
)

data class Roi(
    val id: Int,
    val bbox: List<Int>,
    val params: RoiParams
)

data class Report(
    val reportType: String,
    val start: String,
    val end: String,
    val totalAppearances: Int,
    val uniquePersons: Int,
    val peakHour: Int?,
    val hourlyDistribution: Map<Int, Int>
)

data class RecordingFile(
    val relativePath: String,
    val filename: String,
    val startedAt: String,
    val sizeBytes: Long,
    val modifiedAt: String,
    val deviceId: String = "local",
    val deviceName: String = "当前设备"
)

data class RecordingGroup(
    val day: String,
    val items: List<RecordingFile>
)

data class RecordingSchedule(
    val start: String = "00:00",
    val end: String = "23:59",
    val days: List<Int> = emptyList()
) {
    fun daysText(): String {
        if (days.isEmpty()) return "每天"
        val names = mapOf(1 to "周一", 2 to "周二", 3 to "周三", 4 to "周四", 5 to "周五", 6 to "周六", 7 to "周日")
        return days.sorted().joinToString("、") { names[it] ?: it.toString() }
    }
}

data class RecordingStatus(
    val mode: String = "off",
    val schedule: RecordingSchedule = RecordingSchedule(),
    val scheduleInWindow: Boolean = false,
    val recordingActive: Boolean = false,
    val recordingStatus: String = "-",
    val recordingDeviceId: String = "local",
    val recordingDeviceName: String = "当前设备"
)
