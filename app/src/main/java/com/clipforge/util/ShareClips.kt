package com.clipforge.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.net.toUri
import com.clipforge.data.db.ClipEntity
import com.clipforge.export.ExportMath

/**
 * Opens an exported clip in TikTok / YouTube (when installed) or the system share sheet.
 * Every outcome is reported, so callers can tell the user why nothing opened.
 */
object ShareClips {
    enum class Target(val label: String, val packages: List<String>) {
        TIKTOK("TikTok", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")),
        YOUTUBE("YouTube", listOf("com.google.android.youtube")),
        ANY("Share", emptyList()),
    }

    sealed interface Result {
        /** [recoveredUri] is set when the stored link was missing and the file was found again. */
        data class Opened(val viaChooser: Boolean, val recoveredUri: Uri? = null) : Result
        data object NotExported : Result
        data object FileMissing : Result
        data object NoAppCanShare : Result
    }

    /** Title + hashtags; TikTok ignores text from other apps, so it is also copied to the clipboard. */
    fun caption(clip: ClipEntity): String = listOf(clip.title, clip.hashtags).filter { it.isNotBlank() }.joinToString("\n\n")

    fun installedPackage(context: Context, target: Target): String? = target.packages.firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    fun isInstalled(context: Context, target: Target) = target == Target.ANY || installedPackage(context, target) != null

    private fun readable(context: Context, uri: Uri) =
        runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)

    /**
     * Finds this clip's export in Movies/ClipForge when the stored link is gone (e.g. an older
     * version lost it). Exports are named "NN_<title_slug>.mp4"; the newest match wins.
     */
    fun findExport(context: Context, clip: ClipEntity): Uri? = runCatching {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val suffix = "_" + ExportMath.slug(clip.title) + "%.mp4"
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("%$suffix"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(collection, c.getLong(0)) else null }
    }.getOrNull()

    fun buildSendIntent(clip: ClipEntity, uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, caption(clip))
        putExtra(Intent.EXTRA_TITLE, clip.title)
        // ClipData + the grant flag carry read access through the share sheet to the target app.
        clipData = ClipData.newRawUri(clip.title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun share(
        context: Context,
        clip: ClipEntity,
        target: Target,
        isReadable: (Uri) -> Boolean = { readable(context, it) },
        locate: () -> Uri? = { findExport(context, clip) },
    ): Result {
        val stored = clip.exportedUri?.toUri()
        var recovered: Uri? = null
        val uri = stored?.takeIf(isReadable) ?: locate()?.takeIf(isReadable)?.also { recovered = it }
            ?: return if (clip.exportedAt == null && stored == null) Result.NotExported else Result.FileMissing

        runCatching {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Caption", caption(clip)))
        }
        val send = buildSendIntent(clip, uri)
        val chooser = Intent.createChooser(send, "Share \"${clip.title}\"").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val pkg = installedPackage(context, target)
        if (pkg != null) {
            try {
                context.startActivity(Intent(send).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return Result.Opened(viaChooser = false, recoveredUri = recovered)
            } catch (_: ActivityNotFoundException) {
                // The app is installed but won't take this hand-off: fall back to the share sheet.
            } catch (_: SecurityException) {
            }
        }
        return try {
            context.startActivity(chooser)
            Result.Opened(viaChooser = true, recoveredUri = recovered)
        } catch (_: ActivityNotFoundException) {
            Result.NoAppCanShare
        }
    }

    /** Remembers a recovered export link so the next share doesn't need to search. */
    suspend fun rememberRecovered(context: Context, clip: ClipEntity, r: Result) {
        val uri = (r as? Result.Opened)?.recoveredUri ?: return
        val app = context.applicationContext as? com.clipforge.ClipForgeApp ?: return
        app.container.database.clipDao().markExported(clip.id, uri.toString(), clip.exportedAt ?: System.currentTimeMillis())
    }

    fun message(r: Result): String = when (r) {
        is Result.Opened -> "Caption copied. Paste it into your post."
        Result.NotExported -> "Export this clip first (Export tab)."
        Result.FileMissing -> "Couldn't find the exported video; it may have been deleted. Export it again."
        Result.NoAppCanShare -> "No app on this phone can receive videos."
    }
}
