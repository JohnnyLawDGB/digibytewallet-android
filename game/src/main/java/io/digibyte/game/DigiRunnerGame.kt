package io.digibyte.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.isActive
import kotlin.random.Random

// ── Initial world generation ──────────────────────────────────────────────────

private fun generateInitialState(): GameState {
    val coins = buildList {
        repeat(12) { i ->
            val x = 300f + i * 180f + Random.nextFloat() * 60f
            val y = if (i % 3 == 1) 80f + Random.nextFloat() * 40f else 20f
            add(Coin(x = x, y = y, rotationAngle = Random.nextFloat() * 6.28f))
        }
    }
    val obstacles = buildList {
        repeat(5) { i ->
            val x = 600f + i * 400f + Random.nextFloat() * 100f
            val stackCount = Random.nextInt(1, 4)
            val h = GamePhysics.BTC_COIN_DIAMETER +
                (stackCount - 1) * (GamePhysics.BTC_COIN_DIAMETER - GamePhysics.BTC_STACK_OVERLAP)
            add(Obstacle(x = x, width = GamePhysics.BTC_COIN_DIAMETER, height = h, stackCount = stackCount))
        }
    }
    return GameState(coins = coins, obstacles = obstacles)
}

// ── Spawning helpers ──────────────────────────────────────────────────────────

/** Spawn more coins when the world ahead is getting sparse. */
private fun maybeSpawnCoins(state: GameState): GameState {
    // Look 800px ahead of the current scroll position
    val horizon = state.scrollOffset + 800f
    val furthestCoin = state.coins.maxOfOrNull { it.x } ?: state.scrollOffset
    if (furthestCoin > horizon) return state   // plenty ahead

    // Add a cluster of 3–5 coins just past the horizon
    val clusterX = furthestCoin + 200f + Random.nextFloat() * 100f
    val newCoins = buildList {
        val count = Random.nextInt(3, 6)
        repeat(count) { i ->
            val x = clusterX + i * (GamePhysics.COIN_SIZE * 2.5f)
            val y = if (i % 2 == 0) 20f else 85f + Random.nextFloat() * 30f
            add(Coin(x = x, y = y, rotationAngle = Random.nextFloat() * 6.28f))
        }
    }
    return state.copy(coins = state.coins + newCoins)
}

/** Spawn obstacles when the world ahead is getting sparse. */
private fun maybeSpawnObstacles(state: GameState): GameState {
    val horizon = state.scrollOffset + 900f
    val furthestObs = state.obstacles.maxOfOrNull { it.x } ?: state.scrollOffset
    if (furthestObs > horizon) return state
    if (Random.nextFloat() > 0.5f) return state

    val obsX = furthestObs + 350f + Random.nextFloat() * 250f
    val stackCount = Random.nextInt(1, 4)
    val h = GamePhysics.BTC_COIN_DIAMETER +
        (stackCount - 1) * (GamePhysics.BTC_COIN_DIAMETER - GamePhysics.BTC_STACK_OVERLAP)
    val newObs = Obstacle(x = obsX, width = GamePhysics.BTC_COIN_DIAMETER, height = h, stackCount = stackCount)
    return state.copy(obstacles = state.obstacles + newObs)
}

// ── Main composable ───────────────────────────────────────────────────────────

/**
 * DigiRunner — a side-scrolling runner mini-game that plays during blockchain sync.
 *
 * @param syncProgress 0.0..1.0 — controls scroll speed (faster as sync progresses)
 */
@Composable
fun DigiRunnerGame(
    syncProgress: Float = 0f,
    modifier: Modifier = Modifier
) {
    var gameState by remember { mutableStateOf(generateInitialState()) }
    var lastFrameTime by remember { mutableLongStateOf(0L) }
    val textMeasurer = rememberTextMeasurer()

    // ── Game loop ─────────────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameMillis { frameTime ->
                if (lastFrameTime > 0L) {
                    val deltaRaw = (frameTime - lastFrameTime) / 1000f
                    val delta = deltaRaw.coerceAtMost(0.05f)  // cap at 50 ms to avoid physics explosion

                    var next = GamePhysics.update(gameState, delta, syncProgress)
                    next = maybeSpawnCoins(next)
                    next = maybeSpawnObstacles(next)
                    gameState = next
                }
                lastFrameTime = frameTime
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Press -> {
                                if (gameState.characterY <= GamePhysics.GROUND_Y + 1f) {
                                    gameState = gameState.copy(isHolding = true)
                                }
                            }
                            PointerEventType.Release -> {
                                if (gameState.isHolding) {
                                    gameState = GamePhysics.chargedJump(gameState)
                                }
                            }
                        }
                    }
                }
            }
    ) {
        DisposableEffect(Unit) {
            onDispose {
                gameState = gameState.copy(isHolding = false, holdDuration = 0f)
            }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            // World rendering
            drawBackground(gameState.scrollOffset)
            drawGround(gameState.scrollOffset)
            drawBTCStacks(gameState)
            drawCoins(gameState)
            drawDigiRobot(gameState)
            // HUD
            drawHud(textMeasurer, gameState)
        }
    }
}
