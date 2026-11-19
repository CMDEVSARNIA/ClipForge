package com.clipforge.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.clipforge.data.db.ClipEntity

/** Opens exported clips in TikTok / YouTube (when installed) or the system share sheet. */
object ShareClips {
    enum class Target(val label: String, val packages: List<String>) {
        TIKTOK("TikTok", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")),
        YOUTUBE("YouTube", listOf("com.google.android.youtube")),
        ANY("More…", emptyList()),
    }

    sealed interface Result {
        data object Opened : Result
        data object NotExported : Result
        data object FileMissing : Result
    }

    /** Title + hashtags; TikTok ignores EXTRA_TEXT, so it is also copied to the clipboard. */
    fun caption(clip: ClipEntity): String = listOf(clip.title, clip.hashtags).filter { it.isNotBlank() }.joinToString("\n\n")

    fun installedPackage(context: Context, target: Target): String? = target.packages.firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    fun isInstalled(context: Context, target: Target) = target == Target.ANY || installedPackage(context, target) != null

    private fun readable(context: Context, uri: Uri) =
        runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)

    fun share(context: Context, clip: ClipEntity, target: Target): Result {
        val uri = clip.exportedUri?.toUri() ?: return Result.NotExported
        if (!readable(context, uri)) return Result.FileMissing
        val text = caption(clip)
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Caption", text))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_TITLE, clip.title)
            clipData = ClipData.newRawUri(clip.title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pkg = installedPackage(context, target)
        val intent = if (pkg != null) send.setPackage(pkg) else Intent.createChooser(send, "Share \"${clip.title}\"")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return Result.Opened
    }
}
