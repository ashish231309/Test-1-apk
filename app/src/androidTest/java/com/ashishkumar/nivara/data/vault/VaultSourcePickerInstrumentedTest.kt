package com.ashishkumar.nivara.data.vault

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.ashishkumar.nivara.data.security.JcaSecureRandomSource
import com.ashishkumar.nivara.domain.vault.content.VaultSourceSelectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSourcePickerInstrumentedTest {
    @Test fun pickerIsSingleDocumentAndReturnsOnlyAnEphemeralSafeToken() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pending = PendingSourceDocuments(JcaSecureRandomSource())
        val contract = VaultSourcePickerContract(pending)
        val request = contract.createIntent(context, Unit)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.action)
        assertEquals("*/*", request.type)
        assertTrue(request.hasCategory(Intent.CATEGORY_OPENABLE))
        assertFalse(request.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)

        val source = Uri.parse("content://provider.example/document/42")
        val result = contract.parseResult(
            Activity.RESULT_OK,
            Intent().setData(source).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        ) as VaultSourceSelectionResult.Selected
        assertEquals(source, pending.peek(result.sourceId))
        assertEquals(source, pending.consume(result.sourceId))
        assertEquals(null, pending.peek(result.sourceId))
    }
}
