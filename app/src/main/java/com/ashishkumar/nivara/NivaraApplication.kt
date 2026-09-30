package com.ashishkumar.nivara

import android.app.Application
import com.ashishkumar.nivara.di.DefaultNivaraContainer
import com.ashishkumar.nivara.di.NivaraContainer

class NivaraApplication : Application() {
    val container: NivaraContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DefaultNivaraContainer()
    }
}
