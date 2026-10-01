package com.ashishkumar.nivara.domain.vault.content

import com.ashishkumar.nivara.domain.vault.VaultId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultOrganizationTest {
    private val vaultId = VaultId("00112233445566778899aabbccddeeff")

    @Test fun stableAlbumIdsAndNamesAreValidatedAndMembershipIsOrderedAndUnique() {
        val first = VaultAlbumId("11111111111111111111111111111111")
        val second = VaultAlbumId("22222222222222222222222222222222")
        val itemOne = VaultItemId("00000000000000000000000000000001")
        val itemTwo = VaultItemId("00000000000000000000000000000002")
        val normalizedName = VaultAlbumName.normalize("  Café  ")!!
        val album = VaultAlbum(first, normalizedName, listOf(itemTwo, itemOne))
        assertEquals("Café", normalizedName)
        assertEquals(listOf(itemTwo, itemOne), album.memberItemIds)
        assertEquals("Café", album.name)
        assertEquals(null, VaultAlbumName.normalize("  \t "))
        assertEquals(null, VaultAlbumName.normalize("invalid\u0000name"))
        assertEquals(null, VaultAlbumName.normalize("x".repeat(VaultAlbumName.MAX_CODE_POINTS + 1)))
        assertTrue(VaultAlbumName.normalize("a".repeat(VaultAlbumName.MAX_CODE_POINTS)) != null)
        assertEquals(first, VaultAlbumId.fromBytes(first.toBytes()))
        assertTrue(runCatching { VaultAlbum(first, "dupes", listOf(itemOne, itemOne)) }.isFailure)
        assertFalse(first == second)
    }

    @Test fun organizationCodecIsDeterministicBoundedAndRejectsTamperingAndWrongVault() {
        val albumA = VaultAlbum(
            VaultAlbumId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), "Travel", listOf(id(1), id(2)),
        )
        val albumB = VaultAlbum(
            VaultAlbumId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), "Empty album", emptyList(),
        )
        val snapshot = VaultOrganizationSnapshot(7, listOf(albumB, albumA))
        val first = VaultOrganizationCodec.encode(vaultId, snapshot)
        val second = VaultOrganizationCodec.encode(vaultId, snapshot)
        assertTrue(first.contentEquals(second))
        val decoded = VaultOrganizationCodec.decode(first) as VaultOrganizationCodec.DecodeResult.Decoded
        assertEquals(vaultId, decoded.vaultId)
        assertEquals(snapshot, decoded.snapshot)
        assertTrue(decoded.snapshot.albums.any { it.memberItemIds.isEmpty() })

        val tampered = first.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertEquals(VaultOrganizationCodec.DecodeResult.Invalid, VaultOrganizationCodec.decode(tampered))
        assertEquals(VaultOrganizationCodec.DecodeResult.Invalid, VaultOrganizationCodec.decode(first.copyOf(first.size - 1)))
        assertEquals(VaultOrganizationCodec.DecodeResult.Invalid, VaultOrganizationCodec.decode(first.copyOf(first.size + 1)))
        val oneAlbum = VaultOrganizationCodec.encode(vaultId, VaultOrganizationSnapshot(1, listOf(albumA)))
        val duplicateMembership = oneAlbum.copyOf().also {
            System.arraycopy(it, 61, it, 77, VaultItemId.BYTE_COUNT)
        }
        assertEquals(VaultOrganizationCodec.DecodeResult.Invalid, VaultOrganizationCodec.decode(duplicateMembership))
        val malformedUtf8 = oneAlbum.copyOf().also { it[51] = 0xff.toByte() }
        assertEquals(VaultOrganizationCodec.DecodeResult.Invalid, VaultOrganizationCodec.decode(malformedUtf8))
        assertEquals(VaultOrganizationCodec.DecodeResult.Invalid,
            VaultOrganizationCodec.decode(ByteArray(VaultOrganizationLimits.MAX_PLAINTEXT_BYTES + 1)))
        val future = first.copyOf().also { it[4] = 99 }
        assertEquals(VaultOrganizationCodec.DecodeResult.Unsupported(99), VaultOrganizationCodec.decode(future))
        assertTrue(first.size <= VaultOrganizationLimits.MAX_PLAINTEXT_BYTES)
        listOf(first, second, tampered, future).forEach { it.fill(0) }
    }

    @Test fun envelopeBindsGenerationAndRejectsMalformedLengths() {
        val ciphertext = byteArrayOf(1, 2, 3, 4)
        val encoded = VaultOrganizationEnvelopeCodec.encode(4, ciphertext)
        val record = VaultOrganizationEnvelopeCodec.decode(encoded) as VaultOrganizationEnvelopeCodec.DecodeResult.Valid
        assertEquals(4L, record.record.generation)
        assertTrue(record.record.encryptedEnvelope.contentEquals(ciphertext))
        assertEquals(VaultOrganizationEnvelopeCodec.DecodeResult.Invalid,
            VaultOrganizationEnvelopeCodec.decode(encoded.copyOf(encoded.size - 1)))
        encoded[4] = 3
        assertEquals(VaultOrganizationEnvelopeCodec.DecodeResult.Unsupported(3), VaultOrganizationEnvelopeCodec.decode(encoded))
    }

    @Test fun searchIsUnicodeCaseInsensitiveWhitespaceNormalizedAndMetadataOnly() {
        val items = listOf(
            item(1, "Café Notes.txt", "text/plain"),
            item(2, "invoice.PDF", "application/pdf"),
            item(3, "quiet.bin", null),
        )
        val decomposed = VaultItemSearch.search(items, "  CAFE\u0301   NOTES  ") as VaultSearchState.Matches
        assertEquals(listOf(id(1)), decomposed.items.map { it.id })
        val mime = VaultItemSearch.search(items, " APPLICATION/PDF ") as VaultSearchState.Matches
        assertEquals(listOf(id(2)), mime.items.map { it.id })
        val category = VaultItemSearch.search(items, "  document ") as VaultSearchState.Matches
        assertEquals(listOf(id(1), id(2)), category.items.map { it.id })
        assertTrue(VaultItemSearch.search(items, "") is VaultSearchState.EmptyQuery)
        assertTrue(VaultItemSearch.search(items, "missing") is VaultSearchState.NoMatches)
        assertEquals(VaultSearchState.QueryTooLong,
            VaultItemSearch.search(items, "q".repeat(VaultItemSearch.MAX_QUERY_CODE_POINTS + 1)))
        assertEquals(VaultSearchState.QueryTooLong,
            VaultItemSearch.search(items, String(charArrayOf(0xD800.toChar()))))
        assertEquals(VaultSearchState.IndexMissing, VaultItemSearch.search(VaultIndexRead.Missing, "anything"))
        assertEquals(VaultSearchState.IndexUnreadable, VaultItemSearch.search(VaultIndexRead.Corrupt, "anything"))
        assertEquals(VaultSearchState.IndexUnavailable, VaultItemSearch.search(VaultIndexRead.Unavailable, "anything"))
        assertEquals(VaultSearchState.UnsupportedIndex(4), VaultItemSearch.search(VaultIndexRead.UnsupportedVersion(4), "anything"))
    }

    @Test fun activeAndTrashSearchReuseNormalizationButNeverCrossCollections() {
        val active = item(1, "Visible report.pdf", "application/pdf")
        val trashed = item(2, "Private report.pdf", "application/pdf").withLifecycle(VaultItemLifecycle.TRASHED, 44)
        val index = VaultIndexRead.Ready(8, listOf(active, trashed))
        assertEquals(listOf(id(1)), (VaultItemSearch.search(index, "REPORT") as VaultSearchState.Matches).items.map { it.id })
        assertEquals(VaultSearchState.NoMatches("visible"), VaultItemSearch.searchTrash(index, "visible"))
        assertEquals(listOf(id(2)), (VaultItemSearch.searchTrash(index, "  PRIVATE ") as VaultSearchState.Matches).items.map { it.id })
        assertEquals(listOf(id(1)), (VaultItemSearch.search(listOf(active, trashed), "") as VaultSearchState.EmptyQuery).items.map { it.id })
        assertEquals(listOf(id(2)), (VaultItemSearch.searchTrash(listOf(active, trashed), "") as VaultSearchState.EmptyQuery).items.map { it.id })
    }

    @Test fun trashTimestampSortIsDeterministicAndUsesStableIdForTies() {
        val newer = item(3, "new.txt", "text/plain").withLifecycle(VaultItemLifecycle.TRASHED, 300)
        val tieHighId = item(2, "second.txt", "text/plain").withLifecycle(VaultItemLifecycle.TRASHED, 200)
        val tieLowId = item(1, "first.txt", "text/plain").withLifecycle(VaultItemLifecycle.TRASHED, 200)
        val sort = VaultItemSort(VaultItemSort.Field.TRASH_TIME, VaultItemSort.Direction.DESCENDING)
        assertEquals(listOf(id(3), id(1), id(2)), sort.apply(listOf(tieHighId, newer, tieLowId)).map { it.id })
        assertEquals(listOf(id(3), id(1), id(2)), sort.apply(listOf(tieLowId, tieHighId, newer)).map { it.id })
    }

    @Test fun sortingHasDirectionStableIdTieBreakAndDefaultPreservesInputOrder() {
        val late = item(2, "zeta.txt", "text/plain", 10, 200)
        val early = item(1, "Alpha.txt", "text/plain", 10, 100)
        val duplicate = item(3, "alpha.txt", "application/pdf", 10, 100)
        val input = listOf(late, duplicate, early)
        assertEquals(input, VaultItemSort().apply(input))
        assertEquals(listOf(early, duplicate, late), VaultItemSort(VaultItemSort.Field.NAME).apply(input))
        assertEquals(listOf(late, early, duplicate), VaultItemSort(VaultItemSort.Field.NAME, VaultItemSort.Direction.DESCENDING).apply(input))
        assertEquals(listOf(early, late, duplicate), VaultItemSort(VaultItemSort.Field.SIZE).apply(input))
        assertEquals(listOf(early, duplicate, late), VaultItemSort(VaultItemSort.Field.IMPORT_TIME).apply(input))
        val byType = VaultItemSort(VaultItemSort.Field.TYPE).apply(input)
        assertEquals(setOf(id(1), id(2), id(3)), byType.map { it.id }.toSet())
        assertEquals(listOf("EMPTY", "TRAVEL"), VaultAlbumOrdering.ordered(listOf(
            VaultAlbum(VaultAlbumId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), "Travel", listOf(id(1))),
            VaultAlbum(VaultAlbumId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), "Empty", emptyList()),
        )).map { it.name.uppercase() })
    }

    @Test fun staleMembershipIsExplicitAndReadOnlyResolutionDoesNotMutateAlbum() {
        val album = VaultAlbum(VaultAlbumId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), "Stale", listOf(id(1), id(9)))
        val index = VaultIndexRead.Ready(2, listOf(item(1, "one.txt", "text/plain")))
        val resolved = VaultAlbumMembershipResolver.resolve(album, index)!!
        assertTrue(resolved[0] is VaultAlbumMember.Resolved)
        assertEquals(VaultAlbumMember.Stale(id(9)), resolved[1])
        assertEquals(listOf(id(1), id(9)), album.memberItemIds)
        assertEquals(null, VaultAlbumMembershipResolver.resolve(album, VaultIndexRead.Unavailable))
    }

    @Test fun albumReferencesRemainAcrossTrashAndRestoreButResolveAsInactiveWhileTrashed() {
        val first = VaultAlbum(VaultAlbumId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), "First", listOf(id(1)))
        val second = VaultAlbum(VaultAlbumId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), "Second", listOf(id(1)))
        val active = item(1, "one.txt", "text/plain")
        val trashed = active.withLifecycle(VaultItemLifecycle.TRASHED, 20)
        val inTrash = VaultIndexRead.Ready(3, listOf(trashed))
        assertTrue(VaultAlbumMembershipResolver.resolve(first, inTrash)!!.single() is VaultAlbumMember.Trashed)
        assertTrue(VaultAlbumMembershipResolver.resolve(second, inTrash)!!.single() is VaultAlbumMember.Trashed)
        assertEquals(listOf(id(1)), first.memberItemIds)
        assertEquals(listOf(id(1)), second.memberItemIds)
        val restored = VaultIndexRead.Ready(4, listOf(trashed.withLifecycle(VaultItemLifecycle.ACTIVE)))
        assertTrue(VaultAlbumMembershipResolver.resolve(first, restored)!!.single() is VaultAlbumMember.Resolved)
        assertEquals(id(1), (VaultAlbumMembershipResolver.resolve(second, restored)!!.single() as VaultAlbumMember.Resolved).item.id)
    }

    private fun id(number: Int) = VaultItemId(number.toString(16).padStart(32, '0'))

    private fun item(number: Int, name: String, mime: String?, size: Long = number.toLong(), imported: Long = number.toLong()) =
        VaultItem(id(number), name, mime, size, imported, VaultContentObjectCodec.VERSION,
            VaultContentObjectCodec.HEADER_BYTES + 16L + size, byteArrayOf(1))
}
