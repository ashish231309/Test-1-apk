package com.ashishkumar.nivara.data.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.content.VaultAlbum
import com.ashishkumar.nivara.domain.vault.content.VaultAlbumId
import com.ashishkumar.nivara.domain.vault.content.VaultContentObjectCodec
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRead
import com.ashishkumar.nivara.domain.vault.content.VaultItem
import com.ashishkumar.nivara.domain.vault.content.VaultItemId
import com.ashishkumar.nivara.domain.vault.content.VaultItemSearch
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationCodec
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationSnapshot
import com.ashishkumar.nivara.domain.vault.content.VaultSearchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultOrganizationContractInstrumentedTest {
    @Test fun organizationMetadataRoundTripsOnAndroidRuntimeWithoutItemPayloads() {
        val vaultId = VaultId("00112233445566778899aabbccddeeff")
        val itemId = VaultItemId("00000000000000000000000000000001")
        val album = VaultAlbum(
            VaultAlbumId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            "旅行と Café",
            listOf(itemId),
        )
        val encoded = VaultOrganizationCodec.encode(vaultId, VaultOrganizationSnapshot(1, listOf(album)))
        try {
            val decoded = VaultOrganizationCodec.decode(encoded) as VaultOrganizationCodec.DecodeResult.Decoded
            assertEquals(vaultId, decoded.vaultId)
            assertEquals("旅行と Café", decoded.snapshot.albums.single().name)
            assertEquals(listOf(itemId), decoded.snapshot.albums.single().memberItemIds)
            assertTrue(encoded.size < 1024)
        } finally { encoded.fill(0) }
    }

    @Test fun deviceRuntimeSearchUsesOnlyAuthenticatedIndexMetadataAndTypesUnreadableState() {
        val item = VaultItem(
            VaultItemId("00000000000000000000000000000001"), "Résumé.txt", "text/plain", 10, 20,
            VaultContentObjectCodec.VERSION, VaultContentObjectCodec.HEADER_BYTES + 26L, byteArrayOf(1),
        )
        val state = VaultItemSearch.search(VaultIndexRead.Ready(1, listOf(item)), "  re\u0301sume\u0301  ")
        assertTrue(state is VaultSearchState.Matches)
        assertEquals(VaultSearchState.IndexUnreadable, VaultItemSearch.search(VaultIndexRead.Corrupt, "résumé"))
    }
}
