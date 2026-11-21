package com.clipforge.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.clipforge.desktop.media.Ffmpeg
import com.clipforge.desktop.media.MediaTools
import com.clipforge.desktop.ui.ClipForgeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * End to end with the real FFmpeg on PATH: make a small recording, import it, open it, and render the
 * screens. Set -Dclipforge.shots=<dir> to save PNG screenshots.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopUiTest {
    private val ff = Ffmpeg.locate()
    private val shots = System.getProperty("clipforge.shots")?.let { File(it).apply { mkdirs() } }

    private fun sampleVideo(dir: File): File {
        val out = File(dir, "Ranked match 2026-11-20.mkv")
        runBlocking {
            ff!!.run(ff.ffmpeg, listOf("-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30", "-f", "lavfi", "-i", "sine=frequency=330:sample_rate=48000",
                "-t", "6", "-c:v", "mpeg4", "-q:v", "5", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", out.path))
        }
        return out
    }

    private fun save(name: String, img: java.awt.image.BufferedImage) {
        shots?.let { ImageIO.write(img, "png", File(it, "$name.png")) }
    }

    @Test fun mediaToolsProbeAndGrabFrames() = runBlocking {
        assumeTrue("ffmpeg not on PATH", ff != null)
        val dir = Files.createTempDirectory("cf-media").toFile()
        val v = sampleVideo(dir)
        val tools = MediaTools(ff!!)
        val info = tools.probe(v)
        assertEquals(1280, info.displayWidth)
        assertEquals(30f, info.frameRate)
        assertTrue(info.hasAudio)
        assertTrue(info.durationMs in 5_900..6_200)
        assertEquals(320 * 180 * 4, tools.frameBgra(v, 2_500, 320, 180).size)
        val thumb = tools.thumbnail(v, 1_000, 480, File(dir, "t.jpg"))
        assertTrue(thumb.length() > 1000)
    }

    @Test fun playerPlaysPausesAndSeeks() = runBlocking {
        assumeTrue("ffmpeg not on PATH", ff != null)
        val dir = Files.createTempDirectory("cf-play").toFile()
        val v = sampleVideo(dir)
        val tools = MediaTools(ff!!)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val player = com.clipforge.desktop.media.FfmpegPlayer(tools, v, tools.probe(v), scope)
        try {
            withTimeout(10_000) { while (player.frame.value == null) delay(50) } // paused first frame
            player.play()
            delay(1_800)
            val p1 = player.position.value
            assertTrue("position advanced to $p1", p1 in 800..2_600)
            player.pause()
            delay(300)
            val p2 = player.position.value
            delay(500)
            assertEquals("paused position holds", p2, player.position.value)
            player.seek(4_000)
            assertEquals(4_000L, player.position.value)
            player.play()
            withTimeout(8_000) { while (player.playing.value) delay(50) } // runs to the end and stops
            assertTrue(player.position.value >= 5_900)
            assertEquals(null, player.error.value)
        } finally {
            player.close()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test fun importOpenAndPreview() = runDesktopComposeUiTest(width = 1280, height = 820) {
        assumeTrue("ffmpeg not on PATH", ff != null)
        val dir = Files.createTempDirectory("cf-ui").toFile()
        val app = DesktopApp(File(dir, "data"), ff)
        setContent { ClipForgeTheme { AppRoot(app, pickFiles = { emptyList() }) } }

        onNodeWithText("Drop a recording here").assertExists()
        save("1-empty", onRoot().captureToImage().toAwtImage())

        val video = sampleVideo(dir)
        app.importFiles(listOf(video))
        waitUntil(timeoutMillis = 30_000) { app.library.projects.value.firstOrNull()?.thumbPath != null }
        mainClock.advanceTimeBy(200)
        waitForIdle()
        onNodeWithText("Ranked match 2026-11-20").assertExists()
        save("2-library", onRoot().captureToImage().toAwtImage())

        onNodeWithText("Ranked match 2026-11-20").performClick()
        onNodeWithText("‹  Your videos").assertExists()
        onNodeWithText("1280×720 · 720p").assertExists()
        Thread.sleep(2000) // the first preview frame decodes on a background thread
        mainClock.advanceTimeBy(100)
        save("3-project", onRoot().captureToImage().toAwtImage())
        onNodeWithText("‹  Your videos").performClick()

        onNodeWithText("Settings").performClick()
        onNodeWithText("Connect to your Worker").assertExists()
        save("4-settings", onRoot().captureToImage().toAwtImage())

        app.dragging.value = true
        mainClock.advanceTimeBy(100)
        onNodeWithText("Drop to import").assertExists()
        save("5-drop", onRoot().captureToImage().toAwtImage())
    }
}
