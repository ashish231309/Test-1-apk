package com.ashishkumar.nivara.data.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream

/** Decoder for already-authenticated, size-capped image bytes; returns only a sampled in-memory bitmap. */
internal object AndroidVaultImageDecoder {
    fun decode(encoded: ByteArray): Bitmap? {
        if (encoded.size > AndroidVaultContentPresentationRepository.MAX_IMAGE_COMPRESSED_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(ByteArrayInputStream(encoded), null, bounds)
        val dimensionLimit = AndroidVaultContentPresentationRepository.MAX_IMAGE_DIMENSION
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > dimensionLimit || bounds.outHeight > dimensionLimit) return null
        var sample = 1
        val renderLimit = AndroidVaultContentPresentationRepository.MAX_IMAGE_RENDER_DIMENSION
        while (bounds.outWidth / sample > renderLimit || bounds.outHeight / sample > renderLimit) sample *= 2
        val bitmap = BitmapFactory.decodeStream(
            ByteArrayInputStream(encoded), null,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            },
        ) ?: return null
        if (bitmap.width > renderLimit || bitmap.height > renderLimit ||
            bitmap.width.toLong() * bitmap.height > renderLimit.toLong() * renderLimit
        ) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }
}
