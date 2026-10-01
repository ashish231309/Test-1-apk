package com.ashishkumar.nivara.domain.vault.content

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultContentClassifierTest {
    @Test fun recognizedMimeTypesSelectExpectedCategoryAndPreview() {
        assertClass("image/jpeg", VaultContentCategory.IMAGE, VaultContentClassification.PreviewKind.IMAGE)
        assertClass("video/mp4", VaultContentCategory.VIDEO, VaultContentClassification.PreviewKind.VIDEO)
        assertClass("audio/mpeg", VaultContentCategory.AUDIO, VaultContentClassification.PreviewKind.AUDIO)
        assertClass("application/pdf", VaultContentCategory.DOCUMENT, VaultContentClassification.PreviewKind.PDF)
        assertClass("text/plain", VaultContentCategory.DOCUMENT, VaultContentClassification.PreviewKind.TEXT)
        assertClass("application/vnd.openxmlformats-officedocument.wordprocessingml.document", VaultContentCategory.DOCUMENT, VaultContentClassification.PreviewKind.UNSUPPORTED)
    }

    @Test fun classificationUsesMimeNotFilenameAndFallsBackConservatively() {
        assertEquals(VaultContentCategory.OTHER, VaultContentClassifier.classify("bad mime").category)
        assertEquals(VaultContentCategory.OTHER, VaultContentClassifier.classify(null).category)
        assertEquals(VaultContentCategory.OTHER, VaultContentClassifier.classify("application/octet-stream").category)
        assertEquals(VaultContentCategory.DOCUMENT, VaultContentClassifier.classify("text/unknown").category)
        assertEquals(VaultContentClassification.PreviewKind.UNSUPPORTED, VaultContentClassifier.classify("text/unknown").previewKind)
        assertEquals(VaultContentCategory.VIDEO, VaultContentClassifier.classify("video/x-vnd.fake").category)
        val namedJpeg = item("notes.txt", "image/jpeg")
        val namedVideo = item("movie.mp4", null)
        assertEquals(VaultContentCategory.IMAGE, VaultContentClassifier.classify(namedJpeg).category)
        assertEquals(VaultContentCategory.OTHER, VaultContentClassifier.classify(namedVideo).category)
    }

    private fun assertClass(mime: String, category: VaultContentCategory, preview: VaultContentClassification.PreviewKind) {
        val result = VaultContentClassifier.classify(mime)
        assertEquals(category, result.category)
        assertEquals(preview, result.previewKind)
    }

    private fun item(name: String, mime: String?) = VaultItem(
        id = VaultItemId("0123456789abcdef0123456789abcdef"),
        originalFilename = name,
        originalMimeType = mime,
        originalSizeBytes = 0,
        importedAtEpochMillis = 0,
        objectFormatVersion = VaultContentObjectCodec.VERSION,
        objectSizeBytes = VaultContentObjectCodec.HEADER_BYTES + 16L,
        encryptedItemKey = byteArrayOf(1),
    )
}
