package com.ashishkumar.nivara.data.vault

import android.app.Activity
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ashishkumar.nivara.NivaraApplication
import com.ashishkumar.nivara.domain.vault.VaultRootSelectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultStorageContractInstrumentedTest {
    @Test
    fun pickerUsesSafAndRequestsOnlyPersistableTreeReadWriteAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as NivaraApplication).container
        val contract = VaultRootPickerContract(container.vaultRootSelectionHandler)
        val intent = contract.createIntent(context, Unit)

        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertTrue((intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
        assertTrue((intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0)
        assertTrue((intent.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) != 0)
    }

    @Test
    fun cancellingTheSystemPickerDoesNotSelectOrReplaceARoot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as NivaraApplication).container
        val result = VaultRootPickerContract(container.vaultRootSelectionHandler)
            .parseResult(Activity.RESULT_CANCELED, null)

        assertEquals(VaultRootSelectionResult.CANCELLED, result)
    }
}
