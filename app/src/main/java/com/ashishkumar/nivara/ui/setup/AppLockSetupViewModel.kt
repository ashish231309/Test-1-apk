package com.ashishkumar.nivara.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ashishkumar.nivara.domain.app.ApplicationDiscoveryResult
import com.ashishkumar.nivara.domain.app.ApplicationRepository
import com.ashishkumar.nivara.domain.app.InstalledApplication
import com.ashishkumar.nivara.domain.permissions.UsageAccessRepository
import com.ashishkumar.nivara.domain.permissions.UsageAccessSettingsResult
import com.ashishkumar.nivara.domain.permissions.UsageAccessStatus
import com.ashishkumar.nivara.domain.applock.OverlayCapabilityRepository
import com.ashishkumar.nivara.domain.applock.OverlayCapabilityStatus
import com.ashishkumar.nivara.domain.applock.OverlaySettingsResult
import com.ashishkumar.nivara.domain.setup.AppLockSetupState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AppLockSetupUiState {
    data object Loading : AppLockSetupUiState

    data class Ready(
        val applications: List<InstalledApplication>,
        val setup: AppLockSetupState,
        val overlayCapability: OverlayCapabilityStatus,
        val settingsLaunchResult: UsageAccessSettingsResult? = null,
        val overlaySettingsLaunchResult: OverlaySettingsResult? = null,
    ) : AppLockSetupUiState

    data object Error : AppLockSetupUiState
}

class AppLockSetupViewModel(
    private val applicationRepository: ApplicationRepository,
    private val usageAccessRepository: UsageAccessRepository,
    private val overlayCapabilityRepository: OverlayCapabilityRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow<AppLockSetupUiState>(AppLockSetupUiState.Loading)
    val state = mutableState.asStateFlow()

    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (mutableState.value == AppLockSetupUiState.Loading && refreshJob?.isActive == true) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            mutableState.value = AppLockSetupUiState.Loading
            try {
                val (discovery, usageStatus, overlayStatus) = coroutineScope {
                    val applications = async(Dispatchers.IO) {
                        applicationRepository.discoverLaunchableApplications()
                    }
                    val usage = async(Dispatchers.IO) { usageAccessRepository.status() }
                    val overlay = async(Dispatchers.IO) { overlayCapabilityRepository.status() }
                    Triple(applications.await(), usage.await(), overlay.await())
                }
                val applications = when (discovery) {
                    is ApplicationDiscoveryResult.Available -> discovery.applications
                    ApplicationDiscoveryResult.Unavailable -> emptyList()
                }
                mutableState.value = AppLockSetupUiState.Ready(
                    applications = applications,
                    setup = AppLockSetupState.from(discovery, usageStatus),
                    overlayCapability = overlayStatus,
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                mutableState.value = AppLockSetupUiState.Error
            }
        }
    }

    fun openUsageAccessSettings() {
        val current = mutableState.value as? AppLockSetupUiState.Ready ?: return
        val result = try {
            usageAccessRepository.openSettings()
        } catch (_: RuntimeException) {
            UsageAccessSettingsResult.UNAVAILABLE
        }
        mutableState.value = current.copy(settingsLaunchResult = result)
    }

    fun openOverlayPermissionSettings() {
        val current = mutableState.value as? AppLockSetupUiState.Ready ?: return
        val result = try {
            overlayCapabilityRepository.openSettings()
        } catch (_: RuntimeException) {
            OverlaySettingsResult.FAILED
        }
        mutableState.value = current.copy(overlaySettingsLaunchResult = result)
    }

    class Factory(
        private val applicationRepository: ApplicationRepository,
        private val usageAccessRepository: UsageAccessRepository,
        private val overlayCapabilityRepository: OverlayCapabilityRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AppLockSetupViewModel::class.java))
            return AppLockSetupViewModel(
                applicationRepository,
                usageAccessRepository,
                overlayCapabilityRepository,
            ) as T
        }
    }
}
