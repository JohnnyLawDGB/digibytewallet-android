package io.digibyte.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PinManagerTest {
    private lateinit var pinManager: PinManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        pinManager = PinManager(context)
        pinManager.clearPin()
    }

    @Test
    fun setPin_thenVerify_returnsTrue() {
        pinManager.setPin("123456")
        assertTrue(pinManager.verifyPin("123456") is PinVerifyResult.Success)
    }

    @Test
    fun verifyPin_wrongPin_returnsFalse() {
        pinManager.setPin("123456")
        assertFalse(pinManager.verifyPin("654321") is PinVerifyResult.Success)
    }

    @Test
    fun hasPin_afterSet_returnsTrue() {
        assertFalse(pinManager.hasPin())
        pinManager.setPin("123456")
        assertTrue(pinManager.hasPin())
    }

    @Test
    fun clearPin_removesPin() {
        pinManager.setPin("123456")
        pinManager.clearPin()
        assertFalse(pinManager.hasPin())
    }

    @Test
    fun verifyPin_beforeSet_returnsFalse() {
        assertFalse(pinManager.verifyPin("000000") is PinVerifyResult.Success)
    }

    @Test
    fun setPin_overwrite_verifyNewPin() {
        pinManager.setPin("111111")
        pinManager.setPin("999999")
        assertTrue(pinManager.verifyPin("999999") is PinVerifyResult.Success)
        assertFalse(pinManager.verifyPin("111111") is PinVerifyResult.Success)
    }

    @Test
    fun verifyPin_emptyPin_noMatch() {
        pinManager.setPin("123456")
        assertFalse(pinManager.verifyPin("") is PinVerifyResult.Success)
    }
}
