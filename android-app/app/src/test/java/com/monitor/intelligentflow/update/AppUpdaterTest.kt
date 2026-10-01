package com.monitor.intelligentflow.update

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class AppUpdaterTest {
    private val root = "https://github.com/syczk301/Monitor/releases/download/v4.10.0/"

    @Test fun desktopReleasesNeverBecomeAndroidUpdates() {
        val json = JSONArray("""[{"tag_name":"v99.0.0","assets":[{"name":"CameraMonitor.exe","size":9000000}]}]""")
        assertNull(selectAndroidUpdate(json, 40900))
    }

    @Test fun selectsNewestStableApkAndRejectsDowngrades() {
        val releases = JSONArray("""[
          {"draft":true,"assets":[{"name":"monitor-V4.11.0-release.apk","size":500,"browser_download_url":"${root}draft.apk"}]},
          {"prerelease":true,"assets":[{"name":"monitor-V4.12.0-release.apk","size":500,"browser_download_url":"${root}beta.apk"}]},
          {"body":"new features","assets":[{"name":"monitor-V4.10.0-release.apk","size":500,"browser_download_url":"${root}monitor-V4.10.0-release.apk"},
            {"name":"monitor-V4.10.0-release.apk.sha256","browser_download_url":"${root}monitor-V4.10.0-release.apk.sha256"}]}
        ]""")
        val selected = selectAndroidUpdate(releases, 40900)!!
        assertEquals("4.10.0", selected.version)
        assertNotNull(selected.checksumUrl)
        assertNull(selectAndroidUpdate(releases, 41000))
    }

    @Test fun rejectsForeignDownloadsAndNumericVersionOrderingIsCorrect() {
        assertFalse(validAssetUrl("https://github.com/another/repo/releases/download/v1/app.apk"))
        assertFalse(validAssetUrl("http://github.com/syczk301/Monitor/releases/download/v1/app.apk"))
        assertFalse(validAssetUrl("https://github.com.evil.test/syczk301/Monitor/releases/download/v1/app.apk"))
        assertTrue(versionCode("V4.10.0")!! > versionCode("v4.9.9")!!)
        assertNull(versionCode("v4.10.0-beta"))
    }
}
