package com.clipforge.desktop

import com.clipforge.desktop.data.Library
import com.clipforge.desktop.media.Ffmpeg
import com.clipforge.desktop.media.FfmpegException
import com.clipforge.desktop.media.PreviewSize
import com.clipforge.desktop.media.ProbeParser
import com.clipforge.desktop.settings.DesktopSettings
import com.clipforge.media.VideoInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DesktopCoreTest {
    private fun tmpDir(): File = Files.createTempDirectory("cf-desktop").toFile().apply { deleteOnExit() }

    // ---------- ffprobe parsing ----------
    private val obsJson = """
        {"streams":[
          {"index":0,"codec_name":"h264","codec_type":"video","width":1920,"height":1080,
           "r_frame_rate":"60/1","avg_frame_rate":"60/1","disposition":{"attached_pic":0}},
          {"index":1,"codec_name":"aac","codec_type":"audio","sample_rate":"48000","channels":2}
        ],"format":{"filename":"x.mkv","format_name":"matroska,webm","duration":"3725.480000","size":"9876543210","bit_rate":"21200000"}}
    """.trimIndent()

    @Test fun parsesObsRecording() {
        val i = ProbeParser.parse(obsJson, "x.mkv", null)
        assertEquals(3_725_480L, i.durationMs)
        assertEquals(1920, i.displayWidth)
        assertEquals(60f, i.frameRate)
        assertEquals("video/avc", i.videoMime)
        assertTrue(i.hasAudio)
        assertEquals(9_876_543_210L, i.sizeBytes)
        assertEquals(21_200_000, i.bitrate)
    }

    @Test fun parsesPhoneRotationAndNtscRate() {
        val json = """{"streams":[{"codec_name":"hevc","codec_type":"video","width":1920,"height":1080,
            "avg_frame_rate":"30000/1001","side_data_list":[{"side_data_type":"Display Matrix","rotation":-90}]}],
            "format":{"duration":"12.5"}}"""
        val i = ProbeParser.parse(json, "p.mov", 10)
        assertEquals(90, i.rotationDegrees)
        assertEquals(1080, i.displayWidth)
        assertEquals(1920, i.displayHeight)
        assertEquals(29.97f, i.frameRate)
        assertEquals("video/hevc", i.videoMime)
        assertFalse(i.hasAudio)
        assertEquals(10L, i.sizeBytes)
    }

    @Test fun legacyRotateTagAndCoverArtIsSkipped() {
        val json = """{"streams":[
            {"codec_name":"mjpeg","codec_type":"video","width":600,"height":600,"disposition":{"attached_pic":1}},
            {"codec_name":"h264","codec_type":"video","width":1280,"height":720,"r_frame_rate":"30/1","avg_frame_rate":"0/0","tags":{"rotate":"270"}}],
            "format":{"duration":"5"}}"""
        val i = ProbeParser.parse(json, null, null)
        assertEquals(1280, i.width)
        assertEquals(270, i.rotationDegrees)
        assertEquals(30f, i.frameRate)
    }

    @Test fun audioOnlyFileIsRejected() {
        try {
            ProbeParser.parse("""{"streams":[{"codec_type":"audio"}],"format":{"duration":"5"}}""", null, null)
            fail("expected an exception")
        } catch (e: FfmpegException) {
            assertTrue(e.message!!.contains("No video"))
        }
    }

    @Test fun rateParsing() {
        assertEquals(59.94f, ProbeParser.rate("60000/1001"))
        assertEquals(0f, ProbeParser.rate("0/0"))
        assertEquals(25f, ProbeParser.rate("25"))
        assertEquals(0f, ProbeParser.rate(null))
    }

    // ---------- app folders ----------
    @Test fun dataDirPerOs() {
        val env = mapOf("LOCALAPPDATA" to "C:\\Users\\a\\AppData\\Local")
        assertEquals(File("C:\\Users\\a\\AppData\\Local", "ClipForge"), AppDirs.dataDir(AppDirs.Os.WINDOWS, env::get, "C:\\Users\\a", null))
        assertEquals(File("/Users/a/Library/Application Support/ClipForge"), AppDirs.dataDir(AppDirs.Os.MAC, { null }, "/Users/a", null))
        assertEquals(File("/home/a/.local/share/clipforge"), AppDirs.dataDir(AppDirs.Os.LINUX, { null }, "/home/a", null))
        assertEquals(File("/x/clipforge"), AppDirs.dataDir(AppDirs.Os.LINUX, mapOf("XDG_DATA_HOME" to "/x")::get, "/home/a", null))
        assertEquals(File("/portable"), AppDirs.dataDir(AppDirs.Os.WINDOWS, { null }, "/h", "/portable"))
        assertEquals(AppDirs.Os.WINDOWS, AppDirs.os("Windows 11"))
        assertEquals(AppDirs.Os.MAC, AppDirs.os("Mac OS X"))
        assertEquals(AppDirs.Os.LINUX, AppDirs.os("Linux"))
    }

    // ---------- library ----------
    private fun info(ms: Long = 60_000) = VideoInfo(ms, 1920, 1080, 0, 60f, null, "video/avc", true, 1, "a.mkv")

    @Test fun libraryAddsDedupesPersistsAndRemoves() {
        val dir = tmpDir()
        val f = File(dir, "library.json")
        val lib = Library(f)
        val a = lib.add("/videos/a.mkv", info(), now = 1_000)
        val again = lib.add("/videos/a.mkv", info(), now = 2_000)
        assertEquals(a.id, again.id)
        val b = lib.add("/videos/b.mp4", info(), now = 3_000)
        assertEquals(listOf(b.id, a.id), lib.projects.value.map { it.id })
        assertEquals("a", a.name)

        val reloaded = Library(f)
        assertEquals(2, reloaded.projects.value.size)
        assertEquals(60_000L, reloaded.get(a.id)!!.info.durationMs)

        reloaded.update(reloaded.get(a.id)!!.copy(thumbPath = "/t.jpg"))
        assertEquals("/t.jpg", Library(f).get(a.id)!!.thumbPath)
        reloaded.remove(b.id)
        assertNull(Library(f).get(b.id))
        assertTrue(Library(f).get(a.id)!!.missing)
    }

    @Test fun videoExtensions() {
        assertTrue(Library.isVideo(File("Replay 2026-11-20.MKV")))
        assertTrue(Library.isVideo(File("clip.mp4")))
        assertFalse(Library.isVideo(File("notes.txt")))
    }

    // ---------- settings ----------
    @Test fun settingsPersistAndExposeEndpoint() = runBlocking {
        val f = File(tmpDir(), "settings.json")
        val s = DesktopSettings(f)
        assertNull(s.endpoint())
        val id = s.installId()
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(id))
        assertEquals(id, DesktopSettings(f).installId())
        s.connect("https://w.example.dev", "tok")
        val e = DesktopSettings(f).endpoint()!!
        assertEquals("https://w.example.dev", e.url)
        assertEquals("tok", e.token)
        s.disconnect()
        assertNull(DesktopSettings(f).endpoint())
        assertEquals(id, DesktopSettings(f).installId())
    }

    @Test fun normalizesWorkerUrl() {
        assertEquals("https://a.workers.dev", DesktopSettings.normalizeUrl(" a.workers.dev/ "))
        assertEquals("http://localhost:8787", DesktopSettings.normalizeUrl("http://localhost:8787"))
        assertEquals("", DesktopSettings.normalizeUrl("  "))
    }

    // ---------- preview geometry, drops, ffmpeg lookup ----------
    @Test fun previewSizeFitsAndIsEven() {
        assertEquals(960 to 540, PreviewSize.fit(1920, 1080))
        assertEquals(302 to 540, PreviewSize.fit(1080, 1920))
        assertEquals(640 to 360, PreviewSize.fit(640, 360))
        assertEquals(960 to 410, PreviewSize.fit(2560, 1094))
        assertTrue(PreviewSize.shouldDrop(ptsMs = 0, clockMs = 100, frameMs = 33))
        assertFalse(PreviewSize.shouldDrop(ptsMs = 100, clockMs = 120, frameMs = 33))
    }

    @Test fun droppedFilesAcceptUrisAndPaths() {
        assertEquals(File("/home/a/My Clip.mkv"), fileFromDrop("file:/home/a/My%20Clip.mkv"))
        assertEquals(File("/home/a/x.mp4"), fileFromDrop("/home/a/x.mp4"))
    }

    @Test fun ffmpegLocateNeedsBothTools() {
        val dir = tmpDir()
        val exe = if (AppDirs.os() == AppDirs.Os.WINDOWS) ".exe" else ""
        assertNull(Ffmpeg.locate(listOf(dir)))
        File(dir, "ffmpeg$exe").writeText("")
        assertNull(Ffmpeg.locate(listOf(dir)))
        File(dir, "ffprobe$exe").writeText("")
        assertEquals(dir, Ffmpeg.locate(listOf(File("/nonexistent"), dir))!!.ffmpeg.parentFile)
        assertEquals("12.345", Ffmpeg.seconds(12_345))
    }
}
