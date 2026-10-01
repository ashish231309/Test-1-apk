package com.ashishkumar.nivara.domain.vault.content

import com.ashishkumar.nivara.domain.vault.VaultId
import java.text.Normalizer
import java.util.Locale

/** Stable random album identity. It is never derived from the album name or a storage directory. */
@JvmInline
value class VaultAlbumId(val value: String) {
    init { require(value.matches(Regex("[0-9a-f]{32}"))) }

    fun toBytes(): ByteArray = ByteArray(BYTE_COUNT) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    companion object {
        const val BYTE_COUNT = 16
        fun fromBytes(bytes: ByteArray): VaultAlbumId {
            require(bytes.size == BYTE_COUNT)
            return VaultAlbumId(bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) })
        }
    }
}

/** An album stores only its name and an ordered, duplicate-free list of stable item references. */
class VaultAlbum(
    val id: VaultAlbumId,
    val name: String,
    memberItemIds: List<VaultItemId>,
) {
    private val members = memberItemIds.toList()
    val memberItemIds: List<VaultItemId> get() = members.toList()

    init {
        require(VaultAlbumName.normalize(name) == name)
        require(members.size <= VaultOrganizationLimits.MAX_ITEMS_PER_ALBUM)
        require(members.toSet().size == members.size)
    }

    override fun equals(other: Any?): Boolean = other is VaultAlbum &&
        id == other.id && name == other.name && members == other.members
    override fun hashCode(): Int = 31 * (31 * id.hashCode() + name.hashCode()) + members.hashCode()
    override fun toString(): String = "VaultAlbum(id=$id, name=$name, memberCount=${members.size})"
}

object VaultAlbumName {
    const val MAX_CODE_POINTS = 100
    const val MAX_UTF8_BYTES = 400

    /** Names are NFC-normalized and trimmed; interior whitespace is preserved. */
    fun normalize(value: String): String? {
        val nfc = try { Normalizer.normalize(value, Normalizer.Form.NFC) } catch (_: Exception) { return null }
        var start = 0
        var end = nfc.length
        while (start < end) {
            val cp = nfc.codePointAt(start)
            if (!isWhitespace(cp)) break
            start += Character.charCount(cp)
        }
        while (end > start) {
            val cp = nfc.codePointBefore(end)
            if (!isWhitespace(cp)) break
            end -= Character.charCount(cp)
        }
        val normalized = nfc.substring(start, end)
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length) > MAX_CODE_POINTS) return null
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            if (Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029) return null
            i += Character.charCount(cp)
        }
        val bytes = strictUtf8(normalized) ?: return null
        return normalized.takeIf { bytes.size in 1..MAX_UTF8_BYTES }
    }

    private fun isWhitespace(codePoint: Int): Boolean = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
    private fun strictUtf8(value: String): ByteArray? = try {
        val buffer = Charsets.UTF_8.newEncoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value))
        ByteArray(buffer.remaining()).also(buffer::get)
    } catch (_: Exception) { null }
}

object VaultOrganizationLimits {
    const val MAX_ALBUMS = 1_000
    const val MAX_ITEMS_PER_ALBUM = 20_000
    const val MAX_TOTAL_MEMBERSHIPS = 100_000
    const val MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024
    const val MAX_STORED_RECORD_BYTES = MAX_PLAINTEXT_BYTES + 128
}

/** Deterministic album ordering; duplicate display names remain distinct by stable ID. */
object VaultAlbumOrdering {
    fun ordered(albums: List<VaultAlbum>): List<VaultAlbum> = albums.sortedWith(
        compareBy<VaultAlbum>({ searchKey(it.name) }, { it.id.value }),
    )
}

/** Collection sort selection; DEFAULT preserves the authenticated index's existing item-ID order. */
data class VaultItemSort(
    val field: Field = Field.DEFAULT,
    val direction: Direction = Direction.ASCENDING,
) {
    enum class Field { DEFAULT, NAME, SIZE, IMPORT_TIME, TYPE }
    enum class Direction { ASCENDING, DESCENDING }

    fun apply(items: List<VaultItem>): List<VaultItem> {
        if (field == Field.DEFAULT) return items.toList()
        val rows = items.map { item ->
            SortRow(
                item = item,
                textKey = if (field == Field.NAME) searchKey(item.originalFilename) else "",
                typeKey = if (field == Field.TYPE) VaultContentClassifier.classify(item).category.name else "",
                mimeKey = if (field == Field.TYPE) searchKey(item.originalMimeType.orEmpty()) else "",
                numberKey = when (field) {
                    Field.SIZE -> item.originalSizeBytes
                    Field.IMPORT_TIME -> item.importedAtEpochMillis
                    else -> 0L
                },
            )
        }
        val primary = Comparator<SortRow> { left, right ->
            when (field) {
                Field.DEFAULT -> 0
                Field.NAME -> left.textKey.compareTo(right.textKey)
                Field.SIZE, Field.IMPORT_TIME -> left.numberKey.compareTo(right.numberKey)
                Field.TYPE -> left.typeKey.compareTo(right.typeKey).takeIf { it != 0 }
                    ?: left.mimeKey.compareTo(right.mimeKey)
            }
        }
        val directional = if (direction == Direction.ASCENDING) primary else primary.reversed()
        // ID tie-breaking is intentionally ascending in both directions for stable results.
        return rows.sortedWith(directional.thenBy { it.item.id.value }).map { it.item }
    }
}

private data class SortRow(
    val item: VaultItem,
    val textKey: String,
    val typeKey: String,
    val mimeKey: String,
    val numberKey: Long,
)

sealed interface VaultSearchState {
    data class EmptyQuery(val items: List<VaultItem>) : VaultSearchState
    data class Matches(val normalizedQuery: String, val items: List<VaultItem>) : VaultSearchState
    data class NoMatches(val normalizedQuery: String) : VaultSearchState
    data object QueryTooLong : VaultSearchState
    data object IndexMissing : VaultSearchState
    data object IndexUnavailable : VaultSearchState
    data object IndexUnreadable : VaultSearchState
    data class UnsupportedIndex(val version: Int) : VaultSearchState
    data object VaultUnavailable : VaultSearchState
}

/** Search touches authenticated index metadata only. It never opens an encrypted object. */
object VaultItemSearch {
    const val MAX_QUERY_CODE_POINTS = 256

    fun search(index: VaultIndexRead, query: String): VaultSearchState {
        if (!validQuery(query)) return VaultSearchState.QueryTooLong
        return when (index) {
            is VaultIndexRead.Ready -> search(index.items, query)
            VaultIndexRead.Missing, VaultIndexRead.ObjectsWithoutIndex, is VaultIndexRead.ContentWithoutIndex ->
                VaultSearchState.IndexMissing
            VaultIndexRead.Unavailable -> VaultSearchState.IndexUnavailable
            VaultIndexRead.AccessDenied, VaultIndexRead.Corrupt -> VaultSearchState.IndexUnreadable
            is VaultIndexRead.UnsupportedVersion -> VaultSearchState.UnsupportedIndex(index.version)
            is VaultIndexRead.VaultUnavailable -> VaultSearchState.VaultUnavailable
        }
    }

    fun search(items: List<VaultItem>, query: String): VaultSearchState {
        if (!validQuery(query)) return VaultSearchState.QueryTooLong
        val normalized = normalizeSearchText(query)
        if (normalized.isEmpty()) return VaultSearchState.EmptyQuery(items.toList())
        val matches = items.filter { item ->
            val classification = VaultContentClassifier.classify(item)
            listOfNotNull(
                item.originalFilename,
                item.originalMimeType,
                classification.category.name,
                classification.previewKind.name,
            ).any { normalizeSearchText(it).contains(normalized) }
        }
        return if (matches.isEmpty()) VaultSearchState.NoMatches(normalized)
        else VaultSearchState.Matches(normalized, matches)
    }

    private fun validQuery(query: String): Boolean =
        query.length <= MAX_QUERY_CODE_POINTS * 2 &&
            query.codePointCount(0, query.length) <= MAX_QUERY_CODE_POINTS && isStrictUtf8(query)

    private fun isStrictUtf8(value: String): Boolean = try {
        Charsets.UTF_8.newEncoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value))
        true
    } catch (_: Exception) { false }
}

/** NFC + locale-independent Unicode lowercasing and all-Unicode whitespace collapsing. */
fun normalizeSearchText(value: String): String {
    val canonical = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    val out = StringBuilder(canonical.length)
    var pendingSpace = false
    var index = 0
    while (index < canonical.length) {
        val cp = canonical.codePointAt(index)
        index += Character.charCount(cp)
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
            pendingSpace = out.isNotEmpty()
        } else {
            if (pendingSpace) out.append(' ')
            out.appendCodePoint(cp)
            pendingSpace = false
        }
    }
    return Normalizer.normalize(out.toString(), Normalizer.Form.NFC)
}

private fun searchKey(value: String): String = normalizeSearchText(value)
private fun compareText(left: String, right: String): Int = searchKey(left).compareTo(searchKey(right))

class VaultOrganizationSnapshot(val generation: Long, albums: List<VaultAlbum>) {
    private val albumValues = albums.sortedBy { it.id.value }
    val albums: List<VaultAlbum> get() = albumValues.toList()

    init {
        require(generation >= 0)
        require(albumValues.size <= VaultOrganizationLimits.MAX_ALBUMS)
        require(albumValues.map { it.id }.toSet().size == albumValues.size)
        require(albumValues.sumOf { it.memberItemIds.size.toLong() } <= VaultOrganizationLimits.MAX_TOTAL_MEMBERSHIPS)
    }

    val orderedAlbums: List<VaultAlbum> get() = VaultAlbumOrdering.ordered(albumValues)
    override fun equals(other: Any?): Boolean = other is VaultOrganizationSnapshot &&
        generation == other.generation && albumValues == other.albumValues
    override fun hashCode(): Int = 31 * generation.hashCode() + albumValues.hashCode()
    override fun toString(): String = "VaultOrganizationSnapshot(generation=$generation, albumCount=${albumValues.size})"
}

sealed interface VaultOrganizationRead {
    data class Ready(val snapshot: VaultOrganizationSnapshot) : VaultOrganizationRead
    data object Corrupt : VaultOrganizationRead
    data class UnsupportedVersion(val version: Int?) : VaultOrganizationRead
    data object Unavailable : VaultOrganizationRead
    data object AccessDenied : VaultOrganizationRead
    data object VaultUnavailable : VaultOrganizationRead
}

sealed interface VaultAlbumMutationResult {
    data class Changed(
        val snapshot: VaultOrganizationSnapshot,
        val albumId: VaultAlbumId? = null,
        val oldGenerationsPruned: Boolean = true,
    ) : VaultAlbumMutationResult
    data class InvalidName(val reason: String) : VaultAlbumMutationResult
    data object AlbumLimitReached : VaultAlbumMutationResult
    data object MembershipLimitReached : VaultAlbumMutationResult
    data object AlbumNotFound : VaultAlbumMutationResult
    data object ItemNotFound : VaultAlbumMutationResult
    data object AlreadyMember : VaultAlbumMutationResult
    data object NotMember : VaultAlbumMutationResult
    data object IndexUnavailable : VaultAlbumMutationResult
    data object OrganizationUnavailable : VaultAlbumMutationResult
    data object Corrupt : VaultAlbumMutationResult
    data object UnsupportedVersion : VaultAlbumMutationResult
    data object AccessDenied : VaultAlbumMutationResult
    data object VaultUnavailable : VaultAlbumMutationResult
    data object WriteFailed : VaultAlbumMutationResult
    data object AuthorizationExpired : VaultAlbumMutationResult
}

/** Explicit reference state for an album member; ordinary reads never prune a stale ID. */
sealed interface VaultAlbumMember {
    val itemId: VaultItemId
    data class Resolved(override val itemId: VaultItemId, val item: VaultItem) : VaultAlbumMember
    data class Stale(override val itemId: VaultItemId) : VaultAlbumMember
}

object VaultAlbumMembershipResolver {
    fun resolve(album: VaultAlbum, index: VaultIndexRead): List<VaultAlbumMember>? {
        val ready = index as? VaultIndexRead.Ready ?: return null
        val items = ready.items.associateBy { it.id }
        return album.memberItemIds.map { id -> items[id]?.let { VaultAlbumMember.Resolved(id, it) } ?: VaultAlbumMember.Stale(id) }
    }
}

/** Separately persisted organization metadata; index rows remain the sole source of item metadata. */
interface VaultOrganizationRepository {
    suspend fun inspect(vaultId: VaultId): VaultOrganizationRead
    suspend fun createAlbum(
        vaultId: VaultId,
        name: String,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultAlbumMutationResult
    suspend fun renameAlbum(
        vaultId: VaultId,
        albumId: VaultAlbumId,
        name: String,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultAlbumMutationResult
    suspend fun deleteAlbum(
        vaultId: VaultId,
        albumId: VaultAlbumId,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultAlbumMutationResult
    suspend fun addMembership(
        vaultId: VaultId,
        albumId: VaultAlbumId,
        itemId: VaultItemId,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultAlbumMutationResult
    suspend fun removeMembership(
        vaultId: VaultId,
        albumId: VaultAlbumId,
        itemId: VaultItemId,
        authorizationCheckpoint: suspend () -> Boolean,
    ): VaultAlbumMutationResult
}
