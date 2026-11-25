package com.videograb.app

import com.videograb.app.engine.FormatInfo
import com.videograb.app.engine.FormatOptionsBuilder
import com.videograb.app.engine.FormatParser
import com.videograb.app.engine.MediaInfo
import com.videograb.app.engine.ProgressParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {

    // Shape of real yt-dlp -J output, shortened (separate video + audio, like YouTube / DASH)
    private val dashJson = """
        WARNING: something harmless
        {"id":"abc","title":"Test Video","webpage_url":"https://site/v/abc","extractor_key":"Generic","duration":60.5,
         "formats":[
          {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","filesize":1000000,"tbr":128.0},
          {"format_id":"160","ext":"mp4","vcodec":"avc1.4d400c","acodec":"none","width":256,"height":144,"filesize":500000,"tbr":100.1},
          {"format_id":"136","ext":"mp4","vcodec":"avc1.4d401f","acodec":"none","width":1280,"height":720,"filesize":20000000,"tbr":1500},
          {"format_id":"247","ext":"webm","vcodec":"vp9","acodec":"none","width":1280,"height":720,"filesize":15000000,"tbr":1300},
          {"format_id":"137","ext":"mp4","vcodec":"avc1.640028","acodec":"none","width":1920,"height":1080,"filesize":null,"filesize_approx":40000000,"tbr":3000},
          {"format_id":"313","ext":"webm","vcodec":"vp9","acodec":"none","width":3840,"height":2160,"fps":60,"filesize":90000000}
         ]}
    """.trimIndent()

    // HLS master playlist: every variant already has sound
    private val hlsJson = """
        {"id":"m","title":"HLS","formats":[
          {"format_id":"hls-800","ext":"mp4","protocol":"m3u8_native","width":640,"height":360,"tbr":800,"vcodec":"avc1.4d401e","acodec":"mp4a.40.2"},
          {"format_id":"hls-2500","ext":"mp4","protocol":"m3u8_native","width":1280,"height":720,"tbr":2500,"vcodec":"avc1.4d401f","acodec":"mp4a.40.2"},
          {"format_id":"hls-5000","ext":"mp4","protocol":"m3u8_native","width":1920,"height":1080,"tbr":5000}
        ]}
    """.trimIndent()

    @Test fun parsesSingleVideo() {
        val infos = FormatParser.parse(dashJson)
        assertEquals(1, infos.size)
        val info = infos[0]
        assertEquals("Test Video", info.title)
        assertEquals(60, info.durationSec)
        assertEquals(6, info.formats.size)
        val f137 = info.formats.first { it.id == "137" }
        assertEquals(1080, f137.height)
        assertEquals(40000000L, f137.fileSize) // falls back to filesize_approx
        assertTrue(info.formats.first { it.id == "140" }.audioOnly)
    }

    @Test fun parsesPlaylistEntries() {
        val json = """{"_type":"playlist","title":"Page","entries":[$hlsJson,{"id":"x","title":"Second","url":"https://a/b.mp4","ext":"mp4"}]}"""
        val infos = FormatParser.parse(json)
        assertEquals(2, infos.size)
        assertEquals("HLS", infos[0].title)
        assertEquals(1, infos[1].formats.size)
    }

    @Test fun buildsResolutionList() {
        val opts = FormatOptionsBuilder.build(FormatParser.parse(dashJson)[0], 1080)
        val labels = opts.map { it.label }
        assertEquals(listOf("Best (up to 1080p)", "2160p (4K) 60fps", "1080p", "720p", "144p", "Audio only"), labels)
        // video-only format gets the best audio merged in
        val p720 = opts.first { it.label == "720p" }
        assertEquals("136+ba[ext=m4a]/136+ba/136", p720.format) // H.264 preferred over VP9
        assertTrue(p720.detail.contains("MB"))
        assertTrue(opts.last().audioOnly)
        // 1080p row is pre-selected for a 1080 limit
        assertEquals(2, FormatOptionsBuilder.defaultIndex(opts, 1080))
        assertEquals(0, FormatOptionsBuilder.defaultIndex(opts, 0))
    }

    @Test fun hlsVariantsAreUsedAsIs() {
        val opts = FormatOptionsBuilder.build(FormatParser.parse(hlsJson)[0], 0)
        assertEquals(listOf("Best quality", "1080p", "720p", "360p", "Audio only"), opts.map { it.label })
        assertEquals("hls-5000", opts[1].format)
        assertEquals("hls-800", opts[3].format)
        assertEquals("bv*+ba/b", opts[0].format)
    }

    @Test fun unknownResolutionAndSizeEstimate() {
        val json = """{"title":"t","duration":100,"formats":[
            {"format_id":"0","ext":"ogv"},{"format_id":"1","ext":"mp4","format_note":"SD"}]}"""
        val opts = FormatOptionsBuilder.build(FormatParser.parse(json)[0], 1080)
        assertEquals(listOf("Best (up to 1080p)", "Version 1", "Version 2 (SD)", "Audio only"), opts.map { it.label })

        // 800 kbit/s for 100 s = 10 MB
        val hls = """{"title":"t","duration":100,"formats":[{"format_id":"a","ext":"mp4","height":360,"width":640,"tbr":800}]}"""
        assertTrue(FormatOptionsBuilder.build(FormatParser.parse(hls)[0], 0)[1].detail.contains("~9.5 MB"))
    }

    @Test fun masterPlaylistNames() {
        assertTrue(com.videograb.app.util.UrlUtils.looksLikeMasterPlaylist("https://c/x/master.m3u8?t=1"))
        assertTrue(com.videograb.app.util.UrlUtils.looksLikeMasterPlaylist("https://c/x/index.m3u8"))
        assertTrue(!com.videograb.app.util.UrlUtils.looksLikeMasterPlaylist("https://c/x/720p/seg_list.m3u8"))
    }

    @Test fun separateSoundTrackWithoutCodecIsFound() {
        // Shape of Twitter/X HLS: video variants say "no audio", the sound track has no codec info
        val json = """{"title":"t","duration":60,"formats":[
            {"format_id":"hls-audio-128000-Audio","ext":"mp4","vcodec":"none","acodec":null,"protocol":"m3u8_native"},
            {"format_id":"hls-1080","ext":"mp4","width":1920,"height":1080,"vcodec":"avc1.640028","acodec":"none","tbr":5741},
            {"format_id":"hls-270","ext":"mp4","width":480,"height":270,"vcodec":"avc1.4d001e","acodec":"none","tbr":208}]}"""
        val opts = FormatOptionsBuilder.build(FormatParser.parse(json)[0], 0)
        val p1080 = opts.first { it.label == "1080p" }
        assertEquals("hls-1080+ba[ext=m4a]/hls-1080+ba/hls-1080", p1080.format)
        assertTrue(opts.none { it.detail.contains("no sound") })
    }

    @Test fun portraitVideoUsesShortSide() {
        val info = MediaInfo("p", "", "", listOf(FormatInfo("a", "mp4", width = 1080, height = 1920, vcodec = "avc1", acodec = "aac")))
        val opts = FormatOptionsBuilder.build(info, 0)
        assertEquals("1080p", opts[1].label)
    }

    @Test fun progressLines() {
        val p = ProgressParser.parse("[download]  42.3% of   10.50MiB at    2.01MiB/s ETA 00:04")
        assertNotNull(p)
        assertEquals(42.3f, p!!.percent!!, 0.01f)
        assertEquals("10.50MiB", p.total)
        assertEquals("2.01MiB/s", p.speed)
        assertEquals("00:04", p.eta)

        val hls = ProgressParser.parse("[download]   5.0% of ~ 100.00MiB at  1.00KiB/s ETA 01:10:00 (frag 3/60)")
        assertEquals("100.00MiB", hls!!.total)
        assertEquals("01:10:00", hls.eta)

        assertNull(ProgressParser.parse("[Merger] Merging formats into \"x.mp4\""))
        assertEquals("1.5 MB", ProgressParser.humanSize(1572864))
        assertEquals("", ProgressParser.humanSize(0))
    }
}
