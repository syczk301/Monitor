package com.monitor.intelligentflow

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monitor.intelligentflow.network.EmbeddedTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobilePreviewTest {
    @Test fun originalQualityDisplaysActualFpsAndNewTitle(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val fixture = InstrumentationRegistry.getArguments().getString("previewFixture")
        assumeTrue("Requires the camera-free, original-quality HTTPS preview fixture", fixture != null)
        val repository = AppConfigRepository(instrument.targetContext)
        val original = repository.configFlow.first()
        val config = AppConfig(mode = TransportMode.SYSTEM, system = ConnectionProfile(fixture!!))
        repository.save(config)
        val activity = instrument.startActivitySync(Intent(instrument.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun texts(node: AccessibilityNodeInfo?): List<String> {
            if (node == null) return emptyList()
            return listOfNotNull(node.text?.toString())+(0 until node.childCount).flatMap { texts(node.getChild(it)) }
        }
        fun fullscreenButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == "全屏") return node
            for (i in 0 until node.childCount) fullscreenButton(node.getChild(i))?.let { return it }
            return null
        }
        try {
            var sawTitle = false
            var buttonBounds: Rect? = null
            val samples = mutableListOf<Double>()
            repeat(24) { index ->
                Thread.sleep(1000)
                if (android.os.Build.VERSION.SDK_INT >= 33) instrument.uiAutomation.clearCache()
                val root = instrument.uiAutomation.rootInActiveWindow
                val text = texts(root)
                if (index < 2) fullscreenButton(root)?.let { node ->
                    buttonBounds = Rect().also { node.getBoundsInScreen(it) }
                }
                if (index == 7) {
                    assertNull("Toolbar must hide while video continues", fullscreenButton(root))
                    val bounds = checkNotNull(buttonBounds) { "Initial fullscreen control missing" }
                    val x = bounds.centerX().toFloat() + bounds.width() * 2
                    val y = bounds.centerY().toFloat() - bounds.height() * 2
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0)
                        try { instrument.sendPointerSync(event) } finally { event.recycle() }
                    }
                }
                if (index == 8) assertNotNull("Tap must reveal fullscreen control", fullscreenButton(root))
                if (index == 12) assertNull("Toolbar must hide again after tap", fullscreenButton(root))
                sawTitle = sawTitle || "实时监控" in text
                text.firstOrNull { it.matches(Regex("\\d+\\.\\d")) }?.toDoubleOrNull()?.let { samples += it }
            }
            assertTrue("New title missing",sawTitle)
            val finalFps = samples.takeLast(6).average()
            println("PREVIEW_RENDER_FPS samples=$samples final_average=$finalFps")
            val screenshot = instrument.uiAutomation.takeScreenshot()
            java.io.File(instrument.targetContext.externalCacheDir,"preview-498.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            assertTrue("Original-quality preview too slow: $samples UI=${texts(instrument.uiAutomation.rootInActiveWindow)}", finalFps > 24.0)
        } finally {
            instrument.runOnMainSync { activity.finish() }
            repository.save(original)
            EmbeddedTransport.configure(instrument.targetContext, original)
        }
    }
}
