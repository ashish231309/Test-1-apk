package com.ashishkumar.nivara

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ashishkumar.nivara.ui.NivaraApp
import com.ashishkumar.nivara.ui.theme.NivaraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NivaraTheme {
                NivaraApp((application as NivaraApplication).container.primaryCredentialService)
            }
        }
    }
}
