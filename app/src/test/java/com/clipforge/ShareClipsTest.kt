package com.clipforge

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.clipforge.data.db.ClipEntity
import com.clipforge.util.ShareClips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareClipsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val exported = Uri.parse("content://media/external/video/media/42")
    private val clip = ClipEntity(
        id = 7, projectId = 1, analysisId = 1, rank = 1, tier = "S", score = 92, startMs = 0, endMs = 15_000,
        suggestedStartMs = 0, suggestedEndMs = 15_000, title = "1v4 clutch", hashtags = "#valorant #clutch", why = "",
        firstPassNote = "", hook = null, payoff = null, clarity = null, emotion = null, trendFit = null,
        loudnessAboveMedianDb = 0f, thumbMs = 0, exportedUri = exported.toString(), exportedAt = 1L,
    )

    private fun installTikTok() = shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "com.zhiliaoapp.musically" })
    private fun started(): Intent? = shadowOf(app).nextStartedActivity

    @Test fun tiktokInstalledOpensTikTokDirectlyWithTheVideo() {
        installTikTok()
        val r = ShareClips.share(app, clip, ShareClips.Target.TIKTOK, isReadable = { true }, locate = { null })
        assertEquals(ShareClips.Result.Opened(viaChooser = false), r)
        val i = started()!!
        assertEquals(Intent.ACTION_SEND, i.action)
        assertEquals("com.zhiliaoapp.musically", i.`package`)
        assertEquals("video/mp4", i.type)
        @Suppress("DEPRECATION") assertEquals(exported, i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(exported, i.clipData!!.getItemAt(0).uri) // read access survives the hand-off
        assertTrue(i.getStringExtra(Intent.EXTRA_TEXT)!!.contains("#valorant"))
    }

    @Test fun notInstalledFallsBackToTheShareSheet() {
        val r = ShareClips.share(app, clip, ShareClips.Target.YOUTUBE, isReadable = { true }, locate = { null })
        assertEquals(ShareClips.Result.Opened(viaChooser = true), r)
        val chooser = started()!!
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, inner.action)
        assertNull(inner.`package`)
    }

    @Test fun captionIsCopiedToClipboard() {
        ShareClips.share(app, clip, ShareClips.Target.ANY, isReadable = { true }, locate = { null })
        val cm = app.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals("1v4 clutch\n\n#valorant #clutch", cm.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun lostLinkIsRecoveredFromTheGallery() {
        val found = Uri.parse("content://media/external/video/media/99")
        val r = ShareClips.share(app, clip.copy(exportedUri = null), ShareClips.Target.ANY, isReadable = { it == found }, locate = { found })
        assertEquals(ShareClips.Result.Opened(viaChooser = true, recoveredUri = found), r)
    }

    @Test fun missingFileAndNeverExportedAreReported() {
        assertEquals(ShareClips.Result.FileMissing, ShareClips.share(app, clip, ShareClips.Target.ANY, isReadable = { false }, locate = { null }))
        assertEquals(ShareClips.Result.NotExported, ShareClips.share(app, clip.copy(exportedUri = null, exportedAt = null), ShareClips.Target.ANY, isReadable = { false }, locate = { null }))
        assertNull(started()) // nothing was launched
    }

    @Test fun noReceivingAppIsReportedInsteadOfCrashing() {
        installTikTok()
        shadowOf(app).checkActivities(true) // nothing can resolve: TikTok refuses, chooser has no targets
        val r = ShareClips.share(app, clip, ShareClips.Target.TIKTOK, isReadable = { true }, locate = { null })
        assertEquals(ShareClips.Result.NoAppCanShare, r)
    }
}
