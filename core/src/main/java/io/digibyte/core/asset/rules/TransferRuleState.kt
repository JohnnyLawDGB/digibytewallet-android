package io.digibyte.core.asset.rules

/**
 * Whether a DigiAsset may carry transfer rules (royalty, deflation, signers, vote, KYC, expiry).
 *
 * DigiAsset Core re-checks those rules on every indexer: a transfer that breaks one has the
 * assets on EVERY output cleared and the inputs spent — the sender's whole input holding is
 * destroyed while this wallet, which builds no rule outputs, reports success. So only [NONE]
 * may move, and anything the wallet cannot prove is [UNKNOWN], not "probably fine".
 */
enum class TransferRuleState { NONE, RULE_BOUND, UNKNOWN }

/**
 * The decision, kept pure so every row of the table is a unit test.
 *
 * @param issuanceOpcode  the opcode byte of the issuance the asset walks back to, or null when
 *                        the walk has not reached one (pre-upgrade rows, unresolved chains).
 * @param issuanceLocked  the locked flag of that issuance. Only a LOCKED rule-free issuance is
 *                        final: DigiAsset Core lets a later reissuance of an unlocked aggregatable
 *                        asset attach rules to an asset first issued without them.
 * @param apiRulesPresent true when the asset proxy returned a `rules` object (Core emits the key
 *                        only when the rules are non-empty), false when it answered without one,
 *                        null when no endpoint answered. It can only tighten the verdict.
 */
object AssetTransferRuleGate {
    private val RULE_OPCODES = setOf(3, 4)
    private val RULE_FREE_OPCODES = setOf(1, 2, 5)

    fun stateOf(issuanceOpcode: Int?, issuanceLocked: Boolean?, apiRulesPresent: Boolean?): TransferRuleState {
        if (issuanceOpcode in RULE_OPCODES) return TransferRuleState.RULE_BOUND
        if (apiRulesPresent == true) return TransferRuleState.RULE_BOUND
        if (issuanceOpcode in RULE_FREE_OPCODES && issuanceLocked == true) return TransferRuleState.NONE
        return TransferRuleState.UNKNOWN
    }
}

/** What a screen shows while it resolves the state; [CHECKING] is the only transient value. */
enum class RuleCheckState {
    CHECKING, NONE, RULE_BOUND, UNVERIFIED;

    val allowsSend: Boolean get() = this == NONE

    companion object {
        fun of(state: TransferRuleState): RuleCheckState = when (state) {
            TransferRuleState.NONE -> NONE
            TransferRuleState.RULE_BOUND -> RULE_BOUND
            TransferRuleState.UNKNOWN -> UNVERIFIED
        }
    }
}
