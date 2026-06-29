package io.digibyte.core.recovery

/** Where a sweep sends recovered funds. Default is the current wallet's own
 *  fresh native address; External is the opt-in "send elsewhere" path. */
sealed class SweepDestination {
    object Native : SweepDestination()
    data class External(val address: String) : SweepDestination()
}

sealed class DestResolution {
    data class Ok(val address: String) : DestResolution()
    data class Invalid(val reason: String) : DestResolution()
}

/**
 * Resolve to a concrete, validated destination address.
 * @param nativeSupplier returns the wallet's fresh native (bech32) receive addr.
 * @param validator returns true for a syntactically valid DGB address.
 */
fun SweepDestination.resolve(
    nativeSupplier: () -> String?,
    validator: (String) -> Boolean,
): DestResolution = when (this) {
    is SweepDestination.Native -> {
        val a = nativeSupplier()
        if (a.isNullOrEmpty()) DestResolution.Invalid("Wallet has no receive address yet")
        else DestResolution.Ok(a)
    }
    is SweepDestination.External -> {
        val a = address.trim()
        if (a.isEmpty()) DestResolution.Invalid("Enter an address")
        else if (!validator(a)) DestResolution.Invalid("Not a valid DigiByte address")
        else DestResolution.Ok(a)
    }
}
