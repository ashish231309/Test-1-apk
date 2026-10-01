package com.ashishkumar.nivara.data.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.content.VaultContentObjectCodec
import com.ashishkumar.nivara.domain.vault.content.VaultIndexCodec
import com.ashishkumar.nivara.domain.vault.content.VaultItem
import com.ashishkumar.nivara.domain.vault.content.VaultItemId
import com.ashishkumar.nivara.domain.vault.content.VaultItemLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultTrashContractInstrumentedTest {
    @Test fun versionedIndexRoundTripsTrashIdentityMetadataAndDigestOnAndroidRuntime() {
        val vaultId = VaultId("00112233445566778899aabbccddeeff")
        val id = VaultItemId("11112222333344445555666677778888")
        val digest = ByteArray(VaultItem.SHA_256_BYTES) { it.toByte() }
        val item = VaultItem(
            id = id,
            originalFilename = "same-object.pdf",
            originalMimeType = "application/pdf",
            originalSizeBytes = 512,
            importedAtEpochMillis = 123,
            objectFormatVersion = VaultContentObjectCodec.VERSION,
            objectSizeBytes = VaultContentObjectCodec.HEADER_BYTES + 512 + 16L,
            encryptedItemKey = byteArrayOf(1, 2, 3),
            lifecycle = VaultItemLifecycle.TRASHED,
            trashedAtEpochMillis = 456,
            contentDigestSha256 = digest,
        )
        val decoded = VaultIndexCodec.decode(VaultIndexCodec.encode(vaultId, 9, listOf(item))) as VaultIndexCodec.DecodeResult.Decoded
        val restored = decoded.items.single().withLifecycle(VaultItemLifecycle.ACTIVE)
        assertEquals(id, restored.id)
        assertEquals("same-object.pdf", restored.originalFilename)
        assertEquals(VaultItemLifecycle.ACTIVE, restored.lifecycle)
        assertTrue(restored.contentDigestSha256!!.contentEquals(digest))
    }
}
