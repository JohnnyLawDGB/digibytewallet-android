package io.digibyte.game

data class GameState(
    val characterY: Float = 0f,
    val characterVelocity: Float = 0f,
    val scrollOffset: Float = 0f,
    val coins: List<Coin> = emptyList(),
    val obstacles: List<Obstacle> = emptyList(),
    val score: Int = 0,
    val isJumping: Boolean = false,
    val isGameOver: Boolean = false,
    val highScore: Int = 0,
    val stumbleTimer: Float = 0f,
    // Sprint + crouch + charged jump
    val isHolding: Boolean = false,
    val holdDuration: Float = 0f,
    val sprintMultiplier: Float = 1.0f,
    val crouchAmount: Float = 0f,
    // Lives + game over
    val lives: Int = 3,
    val finalScore: Int = 0
)

data class Coin(val x: Float, val y: Float, val collected: Boolean = false, val rotationAngle: Float = 0f)
data class Obstacle(val x: Float, val width: Float, val height: Float, val hit: Boolean = false, val stackCount: Int = 1)
