package io.digibyte.game

object GamePhysics {
    const val GRAVITY = -1800f         // pixels/sec²
    const val JUMP_VELOCITY = 600f     // pixels/sec
    const val GROUND_Y = 0f
    const val SCROLL_SPEED = 200f      // pixels/sec base speed
    const val CHARACTER_SIZE = 40f     // pixels
    const val COIN_SIZE = 24f
    const val COIN_COLLECT_RADIUS = 30f

    fun update(state: GameState, deltaTime: Float, syncProgress: Float): GameState {
        // Apply gravity
        var newVelocity = state.characterVelocity + GRAVITY * deltaTime
        var newY = state.characterY + newVelocity * deltaTime

        // Ground collision
        if (newY <= GROUND_Y) {
            newY = GROUND_Y
            newVelocity = 0f
        }

        // Scroll world — speed scales slightly with sync progress
        val scrollSpeed = SCROLL_SPEED * (1f + syncProgress * 0.5f)
        val newScroll = state.scrollOffset + scrollSpeed * deltaTime

        // Character X is fixed at ~60px from the left edge; world scrolls past it.
        val charX = 60f

        // Check coin collection
        val prevCollected = state.coins.count { it.collected }
        val updatedCoins = state.coins.map { coin ->
            if (!coin.collected) {
                // Coin's screen-space X = coin.x - newScroll
                val dx = (coin.x - newScroll) - charX
                val dy = coin.y - newY
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist < COIN_COLLECT_RADIUS) coin.copy(collected = true) else coin
            } else coin
        }
        val newlyCollected = updatedCoins.count { it.collected } - prevCollected

        // Cull coins that have scrolled too far off-screen to the left
        val cullThreshold = newScroll - 200f
        val culledCoins = updatedCoins.filter { it.x > cullThreshold }
        val culledObstacles = state.obstacles.filter { it.x > cullThreshold }

        return state.copy(
            characterY = newY,
            characterVelocity = newVelocity,
            scrollOffset = newScroll,
            coins = culledCoins,
            obstacles = culledObstacles,
            score = state.score + newlyCollected,
            isJumping = newY > GROUND_Y
        )
    }

    fun jump(state: GameState): GameState {
        // Only allow jumping from the ground (with a 1-pixel tolerance)
        return if (state.characterY <= GROUND_Y + 1f) {
            state.copy(characterVelocity = JUMP_VELOCITY, isJumping = true)
        } else state
    }
}
