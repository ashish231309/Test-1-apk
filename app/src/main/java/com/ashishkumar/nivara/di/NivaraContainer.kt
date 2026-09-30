package com.ashishkumar.nivara.di

import com.ashishkumar.nivara.data.security.AndroidKeyStoreKeyManager
import com.ashishkumar.nivara.data.security.AesGcmKeyWrappingService
import com.ashishkumar.nivara.data.security.JcaAesGcmEncryption
import com.ashishkumar.nivara.data.security.JcaCredentialKeyDeriver
import com.ashishkumar.nivara.data.security.JcaSecureRandomSource
import com.ashishkumar.nivara.domain.security.AuthenticatedEncryption
import com.ashishkumar.nivara.domain.security.CredentialKeyDeriver
import com.ashishkumar.nivara.domain.security.DeviceKeyStore
import com.ashishkumar.nivara.domain.security.KeyWrappingService
import com.ashishkumar.nivara.domain.security.SecureRandomSource

/** Small hand-written dependency container; no DI framework is needed for the current single-module app. */
interface NivaraContainer {
    val secureRandom: SecureRandomSource
    val encryption: AuthenticatedEncryption
    val keyWrapping: KeyWrappingService
    val credentialKeyDeriver: CredentialKeyDeriver
    val deviceKeyStore: DeviceKeyStore
}

class DefaultNivaraContainer : NivaraContainer {
    override val secureRandom: SecureRandomSource by lazy { JcaSecureRandomSource() }
    override val encryption: AuthenticatedEncryption by lazy { JcaAesGcmEncryption(secureRandom) }
    override val keyWrapping: KeyWrappingService by lazy { AesGcmKeyWrappingService(encryption) }
    override val credentialKeyDeriver: CredentialKeyDeriver by lazy { JcaCredentialKeyDeriver(secureRandom) }
    override val deviceKeyStore: DeviceKeyStore by lazy { AndroidKeyStoreKeyManager() }
}
