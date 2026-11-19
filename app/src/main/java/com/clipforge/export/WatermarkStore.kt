package com.clipforge.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.scale
import com.clipforge.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The optional watermark logo (copied into app storage) and the watermark built from settings. */
class WatermarkStore(private val context: Context) {
    private val file = File(context.filesDir, "watermark_logo.png")

    fun hasLogo() = file.exists()

    fun loadLogo(): Bitmap? = if (file.exists()) BitmapFactory.decodeFile(file.path) else null

    /** Copies a picked image, downscaled to ≤512 px, as PNG (keeps transparency). */
    suspend fun saveLogo(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val src = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@withContext false
        val scale = minOf(1f, 512f / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) src.scale((src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1)) else src
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }

    fun clearLogo() {
        file.delete()
    }

    /** null when the watermark is off or has nothing to show. */
    fun build(s: AppSettings, logo: Bitmap? = loadLogo()): Watermark? {
        if (!s.watermarkEnabled) return null
        if (s.watermarkText.isBlank() && logo == null) return null
        return Watermark(
            text = s.watermarkText,
            logo = logo,
            position = WatermarkPosition.of(s.watermarkPosition),
            opacity = s.watermarkOpacity,
            size = WatermarkSize.of(s.watermarkSize),
        )
    }
}
