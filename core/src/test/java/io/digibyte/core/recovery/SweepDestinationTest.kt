package io.digibyte.core.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepDestinationTest {
    @Test
    fun native_resolvesToWalletAddress() {
        val r = SweepDestination.Native.resolve(
            nativeSupplier = { "dgb1qnative" }, validator = { true })
        assertEquals(DestResolution.Ok("dgb1qnative"), r)
    }

    @Test
    fun native_missingWalletAddress_isInvalid() {
        val r = SweepDestination.Native.resolve(
            nativeSupplier = { null }, validator = { true })
        assertTrue(r is DestResolution.Invalid)
    }

    @Test
    fun external_validAddress_resolves() {
        val r = SweepDestination.External("Dgood").resolve(
            nativeSupplier = { "dgb1qnative" }, validator = { it == "Dgood" })
        assertEquals(DestResolution.Ok("Dgood"), r)
    }

    @Test
    fun external_invalidAddress_isInvalid() {
        val r = SweepDestination.External("xxx").resolve(
            nativeSupplier = { "dgb1qnative" }, validator = { false })
        assertTrue(r is DestResolution.Invalid)
    }

    @Test
    fun external_paddedAddress_trimsBeforeValidation() {
        val r = SweepDestination.External("  Dgood  ").resolve(
            nativeSupplier = { "dgb1qnative" }, validator = { it == "Dgood" })
        assertEquals(DestResolution.Ok("Dgood"), r)
    }

    @Test
    fun external_whitespaceOnlyAddress_isInvalid() {
        val r = SweepDestination.External("   ").resolve(
            nativeSupplier = { "dgb1qnative" }, validator = { true })
        assertTrue(r is DestResolution.Invalid)
    }

    @Test
    fun native_emptyStringSupplier_isInvalid() {
        val r = SweepDestination.Native.resolve(
            nativeSupplier = { "" }, validator = { true })
        assertTrue(r is DestResolution.Invalid)
    }
}
