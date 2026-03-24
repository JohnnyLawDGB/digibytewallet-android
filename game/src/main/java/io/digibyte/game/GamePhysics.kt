package io.digibyte.game

object GamePhysics {
    const val GRAVITY = -1800f         // pixels/sec²
    const val JUMP_VELOCITY = 600f     // pixels/sec
    const val GROUND_Y = 0f
    const val SCROLL_SPEED = 200f      // pixels/sec base speed
    const val CHARACTER_SIZE = 40f     // pixels
    const val COIN_SIZE = 24f
    const val COIN_COLLECT_RADIUS = 30f
    const val STUMBLE_DURATION = 0.8f  // seconds of slow-down on hit
    const val STUMBLE_SPEED_MULT = 0.3f // speed multiplier while stumbling
    const val CHAR_SCREEN_X = 60f      // character's fixed screen X position

    fun update(state: GameState, deltaTime: Float, syncProgress: Float): GameState {
        // Apply gravity
        var newVelocity = state.characterVelocity + GRAVITY * deltaTime
        var newY = state.characterY + newVelocity * deltaTime

        // Ground collision
        if (newY <= GROUND_Y) {
            newY = GROUND_Y
            newVelocity = 0f
        }

        // Stumble timer countdown
        val newStumble = (state.stumbleTimer - deltaTime).coerceAtLeast(0f)
        val isStumbling = newStumble > 0f

        // Scroll world — speed scales slightly with sync progress
        // Slow down during stumble
        val speedMult = if (isStumbling) STUMBLE_SPEED_MULT else 1f
        val scrollSpeed = SCROLL_SPEED * (1f + syncProgress * 0.5f) * speedMult
        val newScroll = state.scrollOffset + scrollSpeed * deltaTime

        // Check coin collection
        val prevCollected = state.coins.count { it.collected }
        val updatedCoins = state.coins.map { coin ->
            if (!coin.collected) {
                val dx = (coin.x - newScroll) - CHAR_SCREEN_X
                val dy = coin.y - newY
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist < COIN_COLLECT_RADIUS) coin.copy(collected = true) else coin
            } else coin
        }
        val newlyCollected = updatedCoins.count { it.collected } - prevCollected

        // Check obstacle collision — character hitbox vs obstacle rect
        var hitObstacle = false
        val charLeft = CHAR_SCREEN_X - CHARACTER_SIZE * 0.25f
        val charRight = CHAR_SCREEN_X + CHARACTER_SIZE * 0.25f
        val charBottom = newY
        val charTop = newY + CHARACTER_SIZE

        val updatedObstacles = state.obstacles.map { obs ->
            if (obs.hit) return@map obs // already hit this one
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

        val finalStumble = if (hitObstacle) STUMBLE_DURATION else newStumble

        // Cull off-screen entities
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
            stumbleTimer = finalStumble
        )
    }

    fun jump(state: GameState): GameState {
        // Only allow jumping from the ground (with a 1-pixel tolerance)
        return if (state.characterY <= GROUND_Y + 1f) {
            state.copy(characterVelocity = JUMP_VELOCITY, isJumping = true)
        } else state
    }
}
