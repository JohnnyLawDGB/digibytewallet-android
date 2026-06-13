package io.digibyte.core.security.adamantine

/**
 * Optional AdamantineOS decision boundary for sensitive wallet actions.
 *
 * This file intentionally contains no Android, JNI, signing, key, network, or
 * broadcast code. The wallet asks for a deterministic decision; AdamantineOS
 * answers allow / deny / require-human-confirmation with reason evidence.
 */
enum class AdamantineDecisionStatus {
    ALLOW,
    DENY,
    REQUIRE_HUMAN_CONFIRMATION
}

data class AdamantineWalletActionRequest(
    val walletId: String,
    val deviceId: String,
    val appId: String,
    val sessionId: String,
    val action: String,
    val intent: String = action,
    val fields: Map<String, String> = emptyMap()
)

data class AdamantineDecision(
    val status: AdamantineDecisionStatus,
    val reasonId: String,
    val contextHash: String? = null,
    val finalPolicyState: String? = null,
    val artifacts: Map<String, Any?> = emptyMap()
) {
    val allowed: Boolean
        get() = status == AdamantineDecisionStatus.ALLOW

    companion object {
        fun allow(
            reasonId: String = REASON_OK_ALLOW,
            contextHash: String? = null,
            finalPolicyState: String? = null,
            artifacts: Map<String, Any?> = emptyMap()
        ): AdamantineDecision = AdamantineDecision(
            status = AdamantineDecisionStatus.ALLOW,
            reasonId = reasonId,
            contextHash = contextHash,
            finalPolicyState = finalPolicyState,
            artifacts = artifacts
        )

        fun deny(
            reasonId: String,
            contextHash: String? = null,
            finalPolicyState: String? = null,
            artifacts: Map<String, Any?> = emptyMap()
        ): AdamantineDecision = AdamantineDecision(
            status = AdamantineDecisionStatus.DENY,
            reasonId = reasonId,
            contextHash = contextHash,
            finalPolicyState = finalPolicyState,
            artifacts = artifacts
        )

        fun requireHumanConfirmation(
            reasonId: String,
            contextHash: String? = null,
            finalPolicyState: String? = FINAL_POLICY_HUMAN_REVIEW_REQUIRED,
            artifacts: Map<String, Any?> = emptyMap()
        ): AdamantineDecision = AdamantineDecision(
            status = AdamantineDecisionStatus.REQUIRE_HUMAN_CONFIRMATION,
            reasonId = reasonId,
            contextHash = contextHash,
            finalPolicyState = finalPolicyState,
            artifacts = artifacts
        )
    }
}

fun interface AdamantineWalletDecisionBoundary {
    /**
     * Evaluate a sensitive wallet action before execution.
     *
     * Implementations must fail closed: malformed requests, unavailable
     * AdamantineOS runtime, malformed responses, timeouts, and unknown states
     * must return DENY, not ALLOW.
     */
    fun evaluate(request: AdamantineWalletActionRequest): AdamantineDecision
}

/**
 * Safe default implementation for this first PR boundary.
 *
 * The adapter is not wired into wallet execution here. If a future caller invokes
 * the boundary while no AdamantineOS runtime is configured, the answer is DENY.
 */
object DisabledAdamantineWalletDecisionBoundary : AdamantineWalletDecisionBoundary {
    override fun evaluate(request: AdamantineWalletActionRequest): AdamantineDecision {
        return AdamantineWalletAdapterInvariants.validate(request)
            ?: AdamantineDecision.deny(REASON_ADAMANTINEOS_ADAPTER_DISABLED)
    }
}

object AdamantineWalletAdapterInvariants {
    private val forbiddenFieldNameTokens = setOf(
        "seed",
        "mnemonic",
        "phrase",
        "privatekey",
        "privkey",
        "xprv",
        "xpub",
        "secret",
        "password",
        "pin",
        "auth",
        "token",
        "keystore",
        "signature",
        "signedtx",
        "unsignedtx",
        "rawtx",
        "serializedtx"
    )

    fun validate(request: AdamantineWalletActionRequest): AdamantineDecision? {
        if (request.walletId.isBlank()) return AdamantineDecision.deny(REASON_ADAMANTINEOS_REQUEST_INVALID)
        if (request.deviceId.isBlank()) return AdamantineDecision.deny(REASON_ADAMANTINEOS_REQUEST_INVALID)
        if (request.appId.isBlank()) return AdamantineDecision.deny(REASON_ADAMANTINEOS_REQUEST_INVALID)
        if (request.sessionId.isBlank()) return AdamantineDecision.deny(REASON_ADAMANTINEOS_REQUEST_INVALID)
        if (request.action.isBlank()) return AdamantineDecision.deny(REASON_ADAMANTINEOS_REQUEST_INVALID)
        if (request.intent.isBlank()) return AdamantineDecision.deny(REASON_ADAMANTINEOS_REQUEST_INVALID)

        val forbiddenKeys = request.fields.keys.filter { isForbiddenFieldName(it) }
        if (forbiddenKeys.isNotEmpty()) {
            return AdamantineDecision.deny(REASON_ADAMANTINEOS_FORBIDDEN_WALLET_MATERIAL)
        }

        return null
    }

    fun isForbiddenFieldName(fieldName: String): Boolean {
        val normalized = fieldName.lowercase().filter { it.isLetterOrDigit() }
        return forbiddenFieldNameTokens.any { token -> normalized.contains(token) }
    }
}

object AdamantineExecutionResponseMapper {
    /**
     * Maps AdamantineOS execution_response_v2-shaped data into the wallet's
     * smaller local decision model. Unknown or malformed shapes fail closed.
     */
    fun fromExecutionResponseV2(payload: Map<String, Any?>): AdamantineDecision {
        val version = payload["v"] as? String
            ?: return AdamantineDecision.deny(REASON_ADAMANTINEOS_RESPONSE_INVALID)

        if (version != "execution_response_v2") {
            return AdamantineDecision.deny(REASON_ADAMANTINEOS_RESPONSE_INVALID)
        }

        val status = payload["status"] as? String
            ?: return AdamantineDecision.deny(REASON_ADAMANTINEOS_RESPONSE_INVALID)

        val reasonId = payload["reason_id"] as? String
            ?: return AdamantineDecision.deny(REASON_ADAMANTINEOS_RESPONSE_INVALID)

        val contextHash = payload["context_hash"] as? String

        val decision = payload["decision"] as? Map<*, *>
            ?: return AdamantineDecision.deny(
                reasonId.ifBlank { REASON_ADAMANTINEOS_RESPONSE_INVALID },
                contextHash
            )

        val allowed = decision["allowed"] as? Boolean
            ?: return AdamantineDecision.deny(REASON_ADAMANTINEOS_RESPONSE_INVALID, contextHash)

        val artifacts = payload["artifacts"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val finalPolicyState = extractFinalPolicyState(artifacts)

        if (finalPolicyState == FINAL_POLICY_HUMAN_REVIEW_REQUIRED) {
            return AdamantineDecision.requireHumanConfirmation(
                reasonId = reasonId,
                contextHash = contextHash,
                finalPolicyState = finalPolicyState,
                artifacts = artifacts.toStringKeyMap()
            )
        }

        return when (status) {
            "allow" -> {
                if (allowed && reasonId == REASON_OK_ALLOW) {
                    AdamantineDecision.allow(
                        reasonId = reasonId,
                        contextHash = contextHash,
                        finalPolicyState = finalPolicyState,
                        artifacts = artifacts.toStringKeyMap()
                    )
                } else {
                    AdamantineDecision.deny(
                        REASON_ADAMANTINEOS_RESPONSE_INVALID,
                        contextHash,
                        finalPolicyState,
                        artifacts.toStringKeyMap()
                    )
                }
            }

            "deny", "error" -> AdamantineDecision.deny(
                reasonId = reasonId.ifBlank { REASON_ADAMANTINEOS_RESPONSE_INVALID },
                contextHash = contextHash,
                finalPolicyState = finalPolicyState,
                artifacts = artifacts.toStringKeyMap()
            )

            else -> AdamantineDecision.deny(
                REASON_ADAMANTINEOS_RESPONSE_INVALID,
                contextHash,
                finalPolicyState,
                artifacts.toStringKeyMap()
            )
        }
    }

    private fun extractFinalPolicyState(artifacts: Map<*, *>): String? {
        val finalPolicy = artifacts["final_policy"] as? Map<*, *> ?: return null
        return finalPolicy["state"] as? String
    }

    private fun Map<*, *>.toStringKeyMap(): Map<String, Any?> =
        entries.mapNotNull { entry ->
            val key = entry.key as? String ?: return@mapNotNull null
            key to entry.value
        }.toMap()
}

const val REASON_OK_ALLOW = "OK_ALLOW"
const val REASON_ADAMANTINEOS_ADAPTER_DISABLED = "DENY_ADAMANTINEOS_ADAPTER_DISABLED"
const val REASON_ADAMANTINEOS_REQUEST_INVALID = "DENY_ADAMANTINEOS_REQUEST_INVALID"
const val REASON_ADAMANTINEOS_RESPONSE_INVALID = "DENY_ADAMANTINEOS_RESPONSE_INVALID"
const val REASON_ADAMANTINEOS_FORBIDDEN_WALLET_MATERIAL = "DENY_ADAMANTINEOS_FORBIDDEN_WALLET_MATERIAL"
const val FINAL_POLICY_HUMAN_REVIEW_REQUIRED = "HUMAN_REVIEW_REQUIRED"
