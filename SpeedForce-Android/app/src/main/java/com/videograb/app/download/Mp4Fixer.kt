package com.videograb.app.download

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Last check before a video goes to the gallery: it must be an .mp4 file.
 * yt-dlp is already told to deliver MP4; this catches anything that still came out as
 * .webm/.mkv/.ts/.flv/... and converts it with the app's own ffmpeg:
 * first a quick repack (seconds, picture and sound untouched), and only if that fails a re-encode.
 */
object Mp4Fixer {
    private const val TAG = "Mp4Fixer"

    /** Returns the .mp4 file to save (the original when it already is one). */
    fun ensure(context: Context, file: File, audioOnly: Boolean, onStage: (String) -> Unit): File {
        val ext = file.extension.lowercase()
        if (ext == "mp4") return file
        if (audioOnly && ext == "m4a") return file // M4A is the MP4 format for sound only
        val out = File(file.parentFile, file.nameWithoutExtension + ".mp4")
        val input = listOf("-hide_banner", "-loglevel", "error", "-y", "-i", file.absolutePath)
        val maps = if (audioOnly) listOf("-vn", "-map", "0:a:0") else listOf("-map", "0:v:0?", "-map", "0:a:0?")
        val tail = listOf("-movflags", "+faststart", out.absolutePath)
        val attempts = if (audioOnly) listOf(
            listOf("-c:a", "copy"),
            listOf("-c:a", "aac", "-b:a", "192k"),
        ) else listOf(
            listOf("-c", "copy"),
            listOf("-c:v", "copy", "-c:a", "aac", "-b:a", "192k"),
            listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "22", "-c:a", "aac", "-b:a", "192k"),
            listOf("-c:v", "mpeg4", "-q:v", "3", "-c:a", "aac", "-b:a", "192k"),
        )
        for ((i, codec) in attempts.withIndex()) {
            onStage(if (i == 0) "Making MP4…" else "Converting to MP4 (this can take a while)…")
            if (run(context, input + maps + codec + tail) && out.length() > 0) {
                file.delete()
                return out
            }
            out.delete()
        }
        throw IllegalStateException("Could not turn '${file.name}' into MP4")
    }

    /** Runs the ffmpeg that comes with the download engine, set up the same way the engine runs it. */
    private fun run(context: Context, args: List<String>): Boolean {
        val bin = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val pkgs = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val libs = listOf("python", "ffmpeg", "aria2c").joinToString(":") { File(pkgs, "$it/usr/lib").absolutePath }
        return try {
            val pb = ProcessBuilder(listOf(bin.absolutePath) + args).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = libs
            val p = pb.start()
            val log = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            if (code != 0) Log.w(TAG, "ffmpeg exit $code: ${log.takeLast(500)}")
            code == 0
        } catch (e: Exception) {
            Log.w(TAG, "ffmpeg failed", e)
            false
        }
    }
}
