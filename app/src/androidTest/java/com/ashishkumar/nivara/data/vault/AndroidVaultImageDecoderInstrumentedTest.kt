package com.ashishkumar.nivara.data.vault

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class AndroidVaultImageDecoderInstrumentedTest {
    @Test fun smallGeneratedPngDecodesWithinPixelBounds() {
        val source = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff336699.toInt()) }
        val bytes = ByteArrayOutputStream().use { output ->
            source.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        source.recycle()
        try {
            val decoded = AndroidVaultImageDecoder.decode(bytes)
            assertNotNull(decoded)
            assertTrue(decoded!!.width <= 3 && decoded.height <= 2)
            decoded.recycle()
        } finally { bytes.fill(0) }
    }

    @Test fun wideImageIsSampledToTheConfiguredRenderDimension() {
        val source = Bitmap.createBitmap(4096, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff112233.toInt()) }
        val bytes = ByteArrayOutputStream().use { output ->
            source.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        source.recycle()
        try {
            val decoded = AndroidVaultImageDecoder.decode(bytes)
            assertNotNull(decoded)
            assertTrue(decoded!!.width <= AndroidVaultContentPresentationRepository.MAX_IMAGE_RENDER_DIMENSION)
            assertTrue(decoded.height <= AndroidVaultContentPresentationRepository.MAX_IMAGE_RENDER_DIMENSION)
            decoded.recycle()
        } finally { bytes.fill(0) }
    }

    @Test fun malformedImageReturnsNoBitmap() {
        val malformed = byteArrayOf(0, 1, 2, 3, 4, 5)
        assertNull(AndroidVaultImageDecoder.decode(malformed))
        malformed.fill(0)
    }
}
