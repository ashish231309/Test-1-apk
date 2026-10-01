package com.ashishkumar.nivara.domain.vault.content

import java.io.InputStream

sealed interface VaultObjectOpenResult {
    data class Opened(val stream: InputStream) : VaultObjectOpenResult
    data object Missing : VaultObjectOpenResult
    data object Unavailable : VaultObjectOpenResult
    data object AccessDenied : VaultObjectOpenResult
}
