package com.monitor.intelligentflow.data

data class Stats(
    val fps: Double = 0.0,
    val avgLatencyMs: Double = 0.0,
    val trackedTargets: Int = 0,
    val gpuUtilization: Double = 0.0,
    val captureStatus: String = "-",
    val captureBackend: String = "-"
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
