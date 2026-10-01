package com.ashishkumar.nivara.data.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidApplicationIconProviderInstrumentedTest {
    @Test
    fun iconLookupUsesPackageManagerAndHandlesMissingPackages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = AndroidApplicationIconProvider(context)

        assertNotNull(provider.loadIcon(context.packageName))
        assertNull(provider.loadIcon("com.nivara.test.package.that.does.not.exist"))
    }
}
