package com.ashishkumar.nivara

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import com.ashishkumar.nivara.ui.NivaraApp
import com.ashishkumar.nivara.ui.theme.NivaraTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val container by lazy { (application as NivaraApplication).container }
    private val foregroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val biometricAuthenticator = container.biometricAuthenticator(this)
        setContent {
            NivaraTheme {
                NivaraApp(container.primaryCredentialService, biometricAuthenticator, container.sessionManager)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foregroundScope.launch { container.sessionManager.currentState() }
    }

    override fun onDestroy() {
        foregroundScope.cancel()
        super.onDestroy()
    }
}
