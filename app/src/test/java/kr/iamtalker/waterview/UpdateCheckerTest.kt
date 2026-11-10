package kr.iamtalker.waterview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {

    @Test fun versionsCompareAsNumbers() {
        assertTrue(UpdateChecker.isNewer("0.2.52", "0.2.51"))
        assertTrue(UpdateChecker.isNewer("v1.5.0", "1.4.9"))
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.9"))
        assertFalse(UpdateChecker.isNewer("1.4.6", "1.4.6"))
        assertFalse(UpdateChecker.isNewer("1.4.5", "1.4.6"))
        assertTrue(UpdateChecker.isNewer("1.4.6.1", "1.4.6"))
        assertFalse(UpdateChecker.isNewer("", "1.4.6"))
    }

    @Test fun parsesReleaseWithApk() {
        val json = """{"tag_name":"v1.4.7","body":"고침","assets":[
            {"name":"notes.txt","browser_download_url":"https://x/notes"},
            {"name":"app-release.apk","browser_download_url":"https://github.com/a/b/releases/download/v1.4.7/app-release.apk"}]}"""
        val release = UpdateChecker.parseRelease(json)!!
        assertEquals("1.4.7", release.version)
        assertTrue(release.downloadUrl!!.endsWith("app-release.apk"))
        assertEquals("고침", release.notes)
    }

    @Test fun releaseWithoutApkKeepsVersion() {
        val release = UpdateChecker.parseRelease("""{"tag_name":"v1.4.7","assets":[]}""")!!
        assertEquals("1.4.7", release.version)
        assertNull(release.downloadUrl)
    }

    @Test fun otherApkNameIsIgnored() {
        val json = """{"tag_name":"v1.4.7","assets":[
            {"name":"app.apk","browser_download_url":"https://x/app.apk"}]}"""
        assertNull(UpdateChecker.parseRelease(json)!!.downloadUrl)
    }

    @Test fun badJsonIsIgnored() {
        assertNull(UpdateChecker.parseRelease("not json"))
    }
}
