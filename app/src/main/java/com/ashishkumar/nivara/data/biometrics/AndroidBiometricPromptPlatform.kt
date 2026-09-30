package com.ashishkumar.nivara.data.biometrics

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.ashishkumar.nivara.domain.biometrics.BiometricAvailability
import com.ashishkumar.nivara.domain.biometrics.BiometricPlatformResult
import com.ashishkumar.nivara.domain.biometrics.BiometricPromptPlatform
import com.ashishkumar.nivara.domain.security.SecureRandomSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.util.concurrent.Executor
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.coroutines.resume

/** AndroidX BiometricPrompt and Android Keystore adapter; no matching or biometric templates are handled here. */
internal class AndroidBiometricPromptPlatform(
    private val activity: FragmentActivity,
    private val manager: BiometricManager,
    private val random: SecureRandomSource,
) : BiometricPromptPlatform {
    private val keyStore = AndroidBiometricKeyStore()
    private val executor: Executor = ContextCompat.getMainExecutor(activity)

    override fun availability(): BiometricAvailability = mapBiometricManagerAvailability(
        manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG),
    )

    override suspend fun authenticate(
        createKey: Boolean,
        continueAfterFailure: suspend () -> Boolean,
    ): BiometricPlatformResult {
        val availability = availability()
        if (availability != BiometricAvailability.AVAILABLE) {
            return BiometricPlatformResult.Unavailable(availability)
        }
        val cipher = try {
            keyStore.createCipher(createKey)
        } catch (failure: KeyPermanentlyInvalidatedException) {
            return BiometricPlatformResult.Invalidated
        } catch (failure: BiometricKeyUnavailableException) {
            return if (createKey) BiometricPlatformResult.SystemError else BiometricPlatformResult.Invalidated
        } catch (failure: InvalidKeyException) {
            return if (createKey) BiometricPlatformResult.SystemError else BiometricPlatformResult.Invalidated
        } catch (failure: GeneralSecurityException) {
            return BiometricPlatformResult.SystemError
        } catch (failure: Exception) {
            return BiometricPlatformResult.SystemError
        }
        val challenge = try {
            random.generateBytes(CHALLENGE_BYTES)
        } catch (failure: Exception) {
            if (createKey) runCatching { keyStore.delete() }
            return BiometricPlatformResult.SystemError
        }

        var keepNewKey = false
        return try {
            when (val result = runPrompt(cipher, continueAfterFailure)) {
                is PromptTerminal.Authenticated -> {
                    val encryptedChallenge = result.cipher.doFinal(challenge)
                    encryptedChallenge.fill(0)
                    keepNewKey = createKey
                    BiometricPlatformResult.Authenticated
                }
                PromptTerminal.UserCancelled -> BiometricPlatformResult.UserCancelled
                PromptTerminal.PrimaryCredentialRequired -> BiometricPlatformResult.PrimaryCredentialRequired
                PromptTerminal.SystemLockedOut -> BiometricPlatformResult.SystemLockedOut
                is PromptTerminal.Unavailable -> BiometricPlatformResult.Unavailable(result.availability)
                PromptTerminal.Invalidated -> BiometricPlatformResult.Invalidated
                PromptTerminal.SystemError -> BiometricPlatformResult.SystemError
                PromptTerminal.ApplicationThrottled -> BiometricPlatformResult.ApplicationThrottled
            }
        } catch (failure: KeyPermanentlyInvalidatedException) {
            BiometricPlatformResult.Invalidated
        } catch (failure: InvalidKeyException) {
            BiometricPlatformResult.Invalidated
        } catch (failure: GeneralSecurityException) {
            BiometricPlatformResult.SystemError
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            BiometricPlatformResult.SystemError
        } finally {
            challenge.fill(0)
            if (createKey && !keepNewKey) runCatching { keyStore.delete() }
        }
    }

    override suspend fun deleteKey() {
        keyStore.delete()
    }

    private suspend fun runPrompt(
        cipher: Cipher,
        continueAfterFailure: suspend () -> Boolean,
    ): PromptTerminal = suspendCancellableCoroutine { continuation ->
        val events = Channel<PromptEvent>(Channel.UNLIMITED)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    events.trySend(PromptEvent.Succeeded(result.cryptoObject?.cipher))
                }

                override fun onAuthenticationFailed() {
                    events.trySend(PromptEvent.Failed)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    events.trySend(PromptEvent.Error(mapError(mapBiometricPromptError(errorCode))))
                }
            },
        )
        var consumeJob: kotlinx.coroutines.Job? = null
        continuation.invokeOnCancellation {
            prompt.cancelAuthentication()
            events.close()
            consumeJob?.cancel()
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(PROMPT_TITLE)
            .setSubtitle(PROMPT_SUBTITLE)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(PRIMARY_FALLBACK_LABEL)
            .build()
        try {
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        } catch (failure: Exception) {
            events.close()
            if (continuation.isActive) continuation.resume(PromptTerminal.SystemError)
        }
        consumeJob = CoroutineScope(continuation.context).launch {
            try {
                while (continuation.isActive) {
                    when (val event = events.receiveCatching().getOrNull() ?: break) {
                        PromptEvent.Failed -> {
                            if (!continueAfterFailure()) {
                                prompt.cancelAuthentication()
                                if (continuation.isActive) {
                                    continuation.resume(PromptTerminal.ApplicationThrottled)
                                }
                                break
                            }
                        }
                        is PromptEvent.Succeeded -> {
                            val authenticatedCipher = event.cipher
                            if (authenticatedCipher == null) {
                                if (continuation.isActive) continuation.resume(PromptTerminal.SystemError)
                            } else if (continuation.isActive) {
                                continuation.resume(PromptTerminal.Authenticated(authenticatedCipher))
                            }
                            break
                        }
                        is PromptEvent.Error -> {
                            if (continuation.isActive) continuation.resume(event.terminal)
                            break
                        }
                    }
                }
            } catch (failure: CancellationException) {
                if (continuation.isActive) continuation.cancel(failure)
            } catch (failure: Exception) {
                prompt.cancelAuthentication()
                if (continuation.isActive) continuation.resume(PromptTerminal.SystemError)
            } finally {
                events.close()
            }
        }
    }

    private fun mapError(result: BiometricPlatformResult): PromptTerminal = when (result) {
        BiometricPlatformResult.Authenticated,
        BiometricPlatformResult.ApplicationThrottled,
        BiometricPlatformResult.SystemError -> PromptTerminal.SystemError
        BiometricPlatformResult.UserCancelled -> PromptTerminal.UserCancelled
        BiometricPlatformResult.PrimaryCredentialRequired -> PromptTerminal.PrimaryCredentialRequired
        BiometricPlatformResult.SystemLockedOut -> PromptTerminal.SystemLockedOut
        is BiometricPlatformResult.Unavailable -> PromptTerminal.Unavailable(result.availability)
        BiometricPlatformResult.Invalidated -> PromptTerminal.Invalidated
    }

    private sealed interface PromptEvent {
        data object Failed : PromptEvent
        data class Succeeded(val cipher: Cipher?) : PromptEvent
        data class Error(val terminal: PromptTerminal) : PromptEvent
    }

    private sealed interface PromptTerminal {
        data class Authenticated(val cipher: Cipher) : PromptTerminal
        data object ApplicationThrottled : PromptTerminal
        data object UserCancelled : PromptTerminal
        data object PrimaryCredentialRequired : PromptTerminal
        data object SystemLockedOut : PromptTerminal
        data class Unavailable(val availability: BiometricAvailability) : PromptTerminal
        data object Invalidated : PromptTerminal
        data object SystemError : PromptTerminal
    }

    private companion object {
        const val CHALLENGE_BYTES = 32
        const val PROMPT_TITLE = "Authenticate with biometrics"
        const val PROMPT_SUBTITLE = "Use your enrolled biometric to continue."
        const val PRIMARY_FALLBACK_LABEL = "Use primary credential"
    }
}

private class AndroidBiometricKeyStore {
    fun createCipher(createKey: Boolean): Cipher {
        if (createKey) create()
        val key = loadKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    fun delete() {
        try {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        } catch (failure: Exception) {
            throw BiometricKeyUnavailableException(failure)
        }
    }

    private fun create() {
        try {
            delete()
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setUserAuthenticationRequired(true)
                .setRandomizedEncryptionRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    } else {
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(-1)
                    }
                }
                .build()
            generator.init(spec)
            generator.generateKey()
        } catch (failure: KeyPermanentlyInvalidatedException) {
            throw failure
        } catch (failure: Exception) {
            throw BiometricKeyUnavailableException(failure)
        }
    }

    private fun loadKey(): SecretKey {
        try {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val entry = store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                ?: throw BiometricKeyUnavailableException()
            return entry.secretKey
        } catch (failure: KeyPermanentlyInvalidatedException) {
            throw failure
        } catch (failure: BiometricKeyUnavailableException) {
            throw failure
        } catch (failure: Exception) {
            if (failure.cause is KeyPermanentlyInvalidatedException) {
                throw KeyPermanentlyInvalidatedException()
            }
            throw BiometricKeyUnavailableException(failure)
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "nivara_biometric_auth_v1"
        const val KEY_SIZE_BITS = 256
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

private class BiometricKeyUnavailableException(cause: Throwable? = null) : Exception(cause)
