package io.digibyte.game

data class GameState(
    val characterY: Float = 0f,       // 0 = ground, positive = up
    val characterVelocity: Float = 0f,
    val scrollOffset: Float = 0f,      // how far the world has scrolled
    val coins: List<Coin> = emptyList(),
    val obstacles: List<Obstacle> = emptyList(),
    val score: Int = 0,
    val isJumping: Boolean = false,
    val isGameOver: Boolean = false,
    val highScore: Int = 0,
    val stumbleTimer: Float = 0f       // >0 = stumbling (seconds remaining)
)

data class Coin(val x: Float, val y: Float, val collected: Boolean = false)
data class Obstacle(val x: Float, val width: Float, val height: Float, val hit: Boolean = false)
