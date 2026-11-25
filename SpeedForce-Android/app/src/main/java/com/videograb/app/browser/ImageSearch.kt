package com.videograb.app.browser

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Search by picture with Google Lens. The photo is shrunk and sent by a page that posts it to Lens;
 * Lens answers with its results page, which then opens like any other page (links open directly).
 * The picture goes to Google and nowhere else.
 */
object ImageSearch {
    private const val MAX_SIDE = 1280

    /** The page that uploads [uri] to Lens, or null when the picture can't be read. */
    fun pageFor(resolver: ContentResolver, uri: Uri): String? {
        val jpeg = runCatching { shrink(resolver, uri) }.getOrNull() ?: return null
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val action = "https://lens.google.com/v3/upload?hl=en&ep=ccm&s=&st=${System.currentTimeMillis()}"
        return """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
<body style="background:#1b1c22;color:#ddd;font:16px sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0">Searching with Google Lens…
<form id="f" method="POST" action="$action" enctype="multipart/form-data"><input type="file" name="encoded_image" id="i" hidden></form>
<script>
var b=atob("$b64"),a=new Uint8Array(b.length);for(var k=0;k<b.length;k++)a[k]=b.charCodeAt(k);
var d=new DataTransfer();d.items.add(new File([a],"image.jpg",{type:"image/jpeg"}));
document.getElementById("i").files=d.files;document.getElementById("f").submit();
</script>"""
    }

    private fun shrink(resolver: ContentResolver, uri: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        var bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val big = maxOf(bmp.width, bmp.height)
        if (big > MAX_SIDE) {
            val f = MAX_SIDE.toFloat() / big
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1), (bmp.height * f).toInt().coerceAtLeast(1), true)
        }
        // Camera photos carry their turn in the file's tags
        val turn = runCatching {
            resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrNull() ?: 0f
        if (turn != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(turn) }, true)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }
}
