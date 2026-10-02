package io.digibyte.ui.asset

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import io.digibyte.R
import io.digibyte.core.asset.AssetTxAmount

/**
 * The count an Android plural is chosen by, for a quantity of [units] at [decimals].
 *
 * Android chooses a plural form by a whole number. For an asset with decimals the whole-unit part
 * is used ("2.50" chooses as 2); CLDR words some fractions differently, which a whole-number
 * choice cannot express. A count beyond Int keeps its last six digits above a million, which
 * keeps every rule the supported languages apply (n % 10, n % 100, and the n % 1,000,000 that
 * French, Spanish and Portuguese use for millions).
 */
fun assetPluralCount(units: Long, decimals: Int): Int {
    var whole = units.coerceAtLeast(0L)
    repeat(decimals.coerceIn(0, 18)) { whole /= 10L }
    return if (whole <= Int.MAX_VALUE) whole.toInt() else (whole % 1_000_000L + 1_000_000L).toInt()
}

/** "tokens" / "token" / "токена"…: the noun for [units] of an asset with no symbol or name. */
@Composable
fun tokensNoun(units: Long, decimals: Int): String =
    pluralStringResource(R.plurals.as_tokens, assetPluralCount(units, decimals))

/** "units" / "unit"…: the noun for [units] of an asset with no symbol. */
@Composable
fun unitsNoun(units: Long, decimals: Int): String =
    pluralStringResource(R.plurals.as_units, assetPluralCount(units, decimals))

/** "20 CHANG", or "1 token" when the asset has no symbol or name: an asset amount as shown. */
@Composable
fun assetAmountText(amount: AssetTxAmount): String =
    amount.quantityText + " " + (amount.label ?: tokensNoun(amount.units, amount.decimals))
