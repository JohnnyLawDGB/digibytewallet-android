package io.digibyte.game

object GamePhysics {
    const val GRAVITY = -1800f
    const val JUMP_VELOCITY = 600f
    const val GROUND_Y = 0f
    const val SCROLL_SPEED = 200f
    const val CHARACTER_SIZE = 48f      // was 40f (+20%)
    const val COIN_SIZE = 29f           // was 24f (+20%)
    const val COIN_COLLECT_RADIUS = 36f // was 30f (+20%)
    const val STUMBLE_DURATION = 0.8f
    const val STUMBLE_SPEED_MULT = 0.3f
    const val CHAR_SCREEN_X = 80f       // was 60f (shifted right for bigger sprite)

    // Sprint mechanics
    const val SPRINT_MAX_MULT = 1.3f
    const val SPRINT_RAMP_TIME = 0.8f   // seconds to reach max sprint
    const val SPRINT_DECAY_RATE = 0.6f  // per second after release
    const val CROUCH_RAMP_RATE = 2.5f   // 0→1 in 0.4s
    const val CROUCH_SNAP_RATE = 5.0f   // 1→0 in 0.2s
    const val JUMP_MIN_SCALE = 0.6f     // tap jump multiplier
    const val JUMP_MAX_SCALE = 1.2f     // charged jump multiplier
    const val JUMP_CHARGE_TIME = 0.8f   // seconds to full charge

    // BTC stack obstacles
    const val BTC_COIN_DIAMETER = 36f
    const val BTC_STACK_OVERLAP = 16f

    fun update(state: GameState, deltaTime: Float, syncProgress: Float): GameState {
        // ── Hold duration & sprint ramp ──
        val newHoldDuration = if (state.isHolding) state.holdDuration + deltaTime else 0f

        val newSprintMult = if (state.isHolding) {
            1f + (newHoldDuration / SPRINT_RAMP_TIME).coerceAtMost(1f) * (SPRINT_MAX_MULT - 1f)
        } else {
            (state.sprintMultiplier - SPRINT_DECAY_RATE * deltaTime).coerceAtLeast(1f)
        }

        val newCrouch = if (state.isHolding) {
            (state.crouchAmount + CROUCH_RAMP_RATE * deltaTime).coerceAtMost(1f)
        } else {
            (state.crouchAmount - CROUCH_SNAP_RATE * deltaTime).coerceAtLeast(0f)
        }

        // ── Gravity ──
        var newVelocity = state.characterVelocity + GRAVITY * deltaTime
        var newY = state.characterY + newVelocity * deltaTime
        if (newY <= GROUND_Y) {
            newY = GROUND_Y
            newVelocity = 0f
        }

        // ── Stumble timer ──
        val newStumble = (state.stumbleTimer - deltaTime).coerceAtLeast(0f)
        val isStumbling = newStumble > 0f

        // ── Scroll ──
        val stumbleMult = if (isStumbling) STUMBLE_SPEED_MULT else 1f
        val scrollSpeed = SCROLL_SPEED * (1f + syncProgress * 0.5f) * newSprintMult * stumbleMult
        val newScroll = state.scrollOffset + scrollSpeed * deltaTime

        // ── Coin collection + rotation ──
        val prevCollected = state.coins.count { it.collected }
        val updatedCoins = state.coins.map { coin ->
            val newAngle = coin.rotationAngle + 3.0f * deltaTime
            if (!coin.collected) {
                val dx = (coin.x - newScroll) - CHAR_SCREEN_X
                val dy = coin.y - newY
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist < COIN_COLLECT_RADIUS) {
                    coin.copy(collected = true, rotationAngle = newAngle)
                } else {
                    coin.copy(rotationAngle = newAngle)
                }
            } else {
                coin.copy(rotationAngle = newAngle)
            }
        }
        val newlyCollected = updatedCoins.count { it.collected } - prevCollected

        // ── Obstacle collision ──
        var hitObstacle = false
        val charLeft = CHAR_SCREEN_X - CHARACTER_SIZE * 0.25f
        val charRight = CHAR_SCREEN_X + CHARACTER_SIZE * 0.25f
        val charBottom = newY
        val charTop = newY + CHARACTER_SIZE

        val updatedObstacles = state.obstacles.map { obs ->
            if (obs.hit) return@map obs
            val obsScreenX = obs.x - newScroll
            val obsLeft = obsScreenX
            val obsRight = obsScreenX + obs.width
            val obsBottom = 0f
            val obsTop = obs.height

            val overlapsX = charRight > obsLeft && charLeft < obsRight
            val overlapsY = charTop > obsBottom && charBottom < obsTop

            if (overlapsX && overlapsY && !isStumbling) {
                hitObstacle = true
                obs.copy(hit = true)
            } else obs
        }

        // Sprint broken on hit
        val finalStumble = if (hitObstacle) STUMBLE_DURATION else newStumble
        val finalHolding = if (hitObstacle) false else state.isHolding
        val finalHoldDur = if (hitObstacle) 0f else newHoldDuration
        val finalSprint = if (hitObstacle) 1f else newSprintMult
        val finalCrouch = if (hitObstacle) 0f else newCrouch

        // ── Cull off-screen ──
        val cullThreshold = newScroll - 200f
        val culledCoins = updatedCoins.filter { it.x > cullThreshold }
        val culledObstacles = updatedObstacles.filter { it.x > cullThreshold }

        return state.copy(
            characterY = newY,
            characterVelocity = newVelocity,
            scrollOffset = newScroll,
            coins = culledCoins,
            obstacles = culledObstacles,
            score = state.score + newlyCollected,
            isJumping = newY > GROUND_Y,
            stumbleTimer = finalStumble,
            isHolding = finalHolding,
            holdDuration = finalHoldDur,
            sprintMultiplier = finalSprint,
            crouchAmount = finalCrouch
        )
    }

    fun jump(state: GameState): GameState {
        // Only allow jumping from the ground (with a 1-pixel tolerance)
        return if (state.characterY <= GROUND_Y + 1f) {
            state.copy(characterVelocity = JUMP_VELOCITY, isJumping = true)
        } else state
    }

    fun chargedJump(state: GameState): GameState {
        if (state.characterY > GROUND_Y + 1f) return state
        val chargeRatio = (state.holdDuration / JUMP_CHARGE_TIME).coerceAtMost(1f)
        val jumpScale = JUMP_MIN_SCALE + chargeRatio * (JUMP_MAX_SCALE - JUMP_MIN_SCALE)
        return state.copy(
            characterVelocity = JUMP_VELOCITY * jumpScale,
            isJumping = true,
            isHolding = false,
            holdDuration = 0f
        )
    }
}
