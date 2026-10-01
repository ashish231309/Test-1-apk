package com.ashishkumar.nivara.data.vault

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ashishkumar.nivara.domain.biometrics.BiometricAuthenticationResult
import com.ashishkumar.nivara.domain.credentials.AuthenticationResult
import com.ashishkumar.nivara.domain.security.SecureRandomSource
import com.ashishkumar.nivara.domain.security.session.AuthenticatedSession
import com.ashishkumar.nivara.domain.security.session.AuthenticationSource
import com.ashishkumar.nivara.domain.security.session.SessionAuthenticationCompletion
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import com.ashishkumar.nivara.domain.vault.VaultId
import com.ashishkumar.nivara.domain.vault.content.VaultContentCrypto
import com.ashishkumar.nivara.domain.vault.content.VaultContentCryptoResult
import com.ashishkumar.nivara.domain.vault.content.VaultContentEncryptionResult
import com.ashishkumar.nivara.domain.vault.content.VaultContentObjectCodec
import com.ashishkumar.nivara.domain.vault.content.VaultContentReadSummary
import com.ashishkumar.nivara.domain.vault.content.VaultContentStorage
import com.ashishkumar.nivara.domain.vault.content.VaultImagePixels
import com.ashishkumar.nivara.domain.vault.content.VaultIndexFileCommitResult
import com.ashishkumar.nivara.domain.vault.content.VaultIndexFiles
import com.ashishkumar.nivara.domain.vault.content.VaultIndexInitializationResult
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRead
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRepository
import com.ashishkumar.nivara.domain.vault.content.VaultIndexWriteResult
import com.ashishkumar.nivara.domain.vault.content.VaultItem
import com.ashishkumar.nivara.domain.vault.content.VaultItemId
import com.ashishkumar.nivara.domain.vault.content.VaultObjectCommitResult
import com.ashishkumar.nivara.domain.vault.content.VaultObjectWriteInfo
import com.ashishkumar.nivara.domain.vault.content.VaultPresentationOpenResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

@RunWith(AndroidJUnit4::class)
class AndroidVaultContentPresentationInstrumentedTest {
    @Test fun quickLockClosesPreviewResourceAfterSuccessfulGatewayOpen() = runBlocking {
        val png = createPng()
        val session = FakeSession()
        val item = testItem(png.size.toLong())
        val storage = FakeContentStorage()
        val index = FakeIndex(item)
        val presenter = AndroidVaultContentPresentationRepository(
            storage = storage,
            crypto = FakeCrypto(png),
            index = index,
            sessions = session,
            random = FakeRandom(),
        )
        try {
            val opened = presenter.open(VAULT_ID, item.id) { session.mayAccessSensitiveContent() }
            assertTrue(opened is VaultPresentationOpenResult.Ready)
            val handle = (opened as VaultPresentationOpenResult.Ready).handle
            val pixels = presenter.image(handle)
            assertNotNull(pixels)
            pixels!!.clear()

            session.lockNow()
            delay(100)
            session.restoreForTest()
            assertNull(presenter.image(handle))
        } finally {
            presenter.close()
            png.fill(0)
        }
    }

    private class FakeSession : SessionManager {
        private val mutableState = MutableStateFlow<SessionState>(authenticated())
        override val sessionState: StateFlow<SessionState> = mutableState
        override suspend fun authenticatePrimary(authenticate: suspend () -> AuthenticationResult) =
            SessionAuthenticationCompletion(AuthenticationResult.Failed, false)
        override suspend fun authenticateBiometric(authenticate: suspend () -> BiometricAuthenticationResult) =
            SessionAuthenticationCompletion(BiometricAuthenticationResult.Failed, false)
        override suspend fun currentState(): SessionState = mutableState.value
        override suspend fun mayAccessSensitiveContent(): Boolean = mutableState.value is SessionState.Authenticated
        override suspend fun lockNow() { mutableState.value = SessionState.Unauthenticated }
        fun restoreForTest() { mutableState.value = authenticated(2) }
    }

    private class FakeContentStorage : VaultContentStorage {
        override suspend fun initializeContentDirectories() = VaultIndexFileCommitResult.WriteFailed
        override suspend fun inspectIndexFiles() = VaultIndexFiles.Unavailable
        override suspend fun readIndexFile(generation: Long): ByteArray? = null
        override suspend fun commitIndexFileAtomically(
            generation: Long,
            bytes: ByteArray,
            authorizationCheckpoint: suspend () -> Boolean,
        ) = VaultIndexFileCommitResult.WriteFailed
        override suspend fun writeObjectAtomically(
            itemId: VaultItemId,
            authorizationCheckpoint: suspend () -> Boolean,
            writer: suspend (OutputStream) -> VaultObjectWriteInfo,
        ) = VaultObjectCommitResult.WriteFailed
        override suspend fun openObject(itemId: VaultItemId): InputStream = ByteArrayInputStream(byteArrayOf(1))
    }

    private class FakeIndex(private val item: VaultItem) : VaultIndexRepository {
        override suspend fun inspect(vaultId: VaultId): VaultIndexRead = VaultIndexRead.Ready(0, listOf(item))
        override suspend fun initializeEmpty(
            vaultId: VaultId,
            authorizationCheckpoint: suspend () -> Boolean,
        ) = VaultIndexInitializationResult.WriteFailed
        override suspend fun listItems(vaultId: VaultId): VaultIndexRead = inspect(vaultId)
        override suspend fun addItem(
            vaultId: VaultId,
            item: VaultItem,
            authorizationCheckpoint: suspend () -> Boolean,
        ) = VaultIndexWriteResult.Failed
    }

    /** Supplies a successful quarantine result to exercise platform decoding/cleanup, not the GCM implementation. */
    private class FakeCrypto(private val plaintext: ByteArray) : VaultContentCrypto {
        override suspend fun encryptIndex(
            vaultId: VaultId,
            generation: Long,
            plaintext: ByteArray,
        ): VaultContentCryptoResult<ByteArray> = VaultContentCryptoResult.OperationFailed
        override suspend fun decryptIndex(
            vaultId: VaultId,
            generation: Long,
            encoded: ByteArray,
        ): VaultContentCryptoResult<ByteArray> = VaultContentCryptoResult.OperationFailed
        override suspend fun encryptObject(
            vaultId: VaultId,
            itemId: VaultItemId,
            source: InputStream,
            destination: OutputStream,
            expectedSourceSize: Long?,
            authorizationCheckpoint: suspend () -> Boolean,
            onProgress: (Long) -> Unit,
        ): VaultContentCryptoResult<VaultContentEncryptionResult> = VaultContentCryptoResult.OperationFailed
        override suspend fun verifyObject(
            vaultId: VaultId,
            item: VaultItem,
            source: InputStream,
            authorizationCheckpoint: suspend () -> Boolean,
        ): VaultContentCryptoResult<Boolean> = VaultContentCryptoResult.OperationFailed
        override suspend fun decryptObjectToQuarantine(
            vaultId: VaultId,
            item: VaultItem,
            source: InputStream,
            destination: OutputStream,
            authorizationCheckpoint: suspend () -> Boolean,
        ): VaultContentCryptoResult<VaultContentReadSummary> {
            if (!authorizationCheckpoint()) return VaultContentCryptoResult.AuthorizationExpired
            destination.write(plaintext)
            return VaultContentCryptoResult.Success(VaultContentReadSummary(plaintext.size.toLong(), item.objectSizeBytes))
        }
    }

    private class FakeRandom : SecureRandomSource {
        private var next = 0
        override fun generateBytes(size: Int): ByteArray = ByteArray(size) { (next++ and 0xff).toByte() }
    }

    companion object {
        private val VAULT_ID = VaultId("00112233445566778899aabbccddeeff")
        private fun authenticated(authenticatedAt: Long = 1) = SessionState.Authenticated(
            AuthenticatedSession(AuthenticationSource.PRIMARY, authenticatedAt, Long.MAX_VALUE),
        )
        private fun testItem(size: Long) = VaultItem(
            id = VaultItemId("0123456789abcdef0123456789abcdef"),
            originalFilename = "photo.png",
            originalMimeType = "image/png",
            originalSizeBytes = size,
            importedAtEpochMillis = 1,
            objectFormatVersion = VaultContentObjectCodec.VERSION,
            objectSizeBytes = VaultContentObjectCodec.HEADER_BYTES + 16L + size,
            encryptedItemKey = byteArrayOf(1),
        )
        private fun createPng(): ByteArray {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff336699.toInt()) }
            return try {
                ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
