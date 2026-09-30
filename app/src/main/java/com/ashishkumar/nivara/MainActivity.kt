package com.ashishkumar.nivara

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import com.ashishkumar.nivara.ui.NivaraApp
import com.ashishkumar.nivara.ui.theme.NivaraTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as NivaraApplication).container
        val biometricAuthenticator = container.biometricAuthenticator(this)
        setContent {
            NivaraTheme {
                NivaraApp(container.primaryCredentialService, biometricAuthenticator)
            }
        }
    }
}
