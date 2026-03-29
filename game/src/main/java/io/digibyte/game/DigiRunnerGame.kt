package io.digibyte.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.isActive
import kotlin.random.Random

// ── Coin pattern generators ──────────────────────────────────────────────────
//
// Jump physics reference (determines reachable coin heights):
//   Tap jump:     v=360, peak=36px,  airtime=0.4s, horiz=80-144px
//   Charged jump: v=720, peak=144px, airtime=0.8s, horiz=160-288px
//   Coin spacing must fit within horizontal travel during a single jump.

private val coinSpacing = GamePhysics.COIN_SIZE * 1.6f  // ~61px between coins

/** Long ground-level sprint run — reward for holding sprint. */
private fun coinPatternSprintRun(startX: Float): List<Coin> = buildList {
    val count = Random.nextInt(8, 14)
    repeat(count) { i ->
        add(Coin(x = startX + i * coinSpacing, y = 15f,
            rotationAngle = Random.nextFloat() * 6.28f))
    }
}

/** Gentle ascending staircase — fits within a charged jump arc. */
private fun coinPatternStairUp(startX: Float): List<Coin> = buildList {
    val steps = Random.nextInt(4, 7)
    val stepHeight = 20f  // total climb: 4-6 steps × 20 = 80-120px (within 144px max)
    repeat(steps) { i ->
        add(Coin(x = startX + i * coinSpacing, y = 15f + i * stepHeight,
            rotationAngle = Random.nextFloat() * 6.28f))
    }
}

/** Descending staircase — come down from a peak. */
private fun coinPatternStairDown(startX: Float): List<Coin> = buildList {
    val steps = Random.nextInt(4, 7)
    val stepHeight = 20f
    val peakY = 15f + (steps - 1) * stepHeight
    repeat(steps) { i ->
        add(Coin(x = startX + i * coinSpacing, y = peakY - i * stepHeight,
            rotationAngle = Random.nextFloat() * 6.28f))
    }
}

/** Arc — matches actual jump parabola so a well-timed jump collects them all. */
private fun coinPatternArc(startX: Float): List<Coin> = buildList {
    val count = Random.nextInt(5, 8)
    // Peak height reachable by a medium-charged jump
    val peakHeight = 80f + Random.nextFloat() * 50f  // 80-130px (charged jump reaches 144)
    // Total horizontal span should match jump travel (~200-250px)
    val totalWidth = count * coinSpacing
    repeat(count) { i ->
        val t = i.toFloat() / (count - 1).coerceAtLeast(1)
        val y = 15f + peakHeight * 4f * t * (1f - t)  // parabolic arc
        add(Coin(x = startX + i * coinSpacing, y = y,
            rotationAngle = Random.nextFloat() * 6.28f))
    }
}

/** High cluster — at charged-jump peak, tight group. */
private fun coinPatternHighCluster(startX: Float): List<Coin> = buildList {
    val count = Random.nextInt(3, 5)
    val baseY = 90f + Random.nextFloat() * 30f  // 90-120px, needs charged jump
    repeat(count) { i ->
        add(Coin(x = startX + i * coinSpacing * 0.8f, y = baseY,
            rotationAngle = Random.nextFloat() * 6.28f))
    }
}

/** Mixed ground + air — some coins low, some high, variety within one pattern. */
private fun coinPatternMixed(startX: Float): List<Coin> = buildList {
    // Ground run with a few airborne bonuses
    val count = Random.nextInt(6, 10)
    repeat(count) { i ->
        val y = if (i % 3 == 0) 60f + Random.nextFloat() * 40f else 15f
        add(Coin(x = startX + i * coinSpacing, y = y,
            rotationAngle = Random.nextFloat() * 6.28f))
    }
}

/** Pick a random coin pattern. */
private fun generateCoinPattern(startX: Float): List<Coin> {
    return when (Random.nextInt(6)) {
        0 -> coinPatternSprintRun(startX)
        1 -> coinPatternStairUp(startX)
        2 -> coinPatternStairDown(startX)
        3 -> coinPatternArc(startX)
        4 -> coinPatternHighCluster(startX)
        else -> coinPatternMixed(startX)
    }
}

// ── Obstacle helper ──────────────────────────────────────────────────────────

private fun makeObstacle(x: Float): Obstacle {
    val stackCount = Random.nextInt(1, 4)
    val h = GamePhysics.BTC_COIN_DIAMETER +
        (stackCount - 1) * (GamePhysics.BTC_COIN_DIAMETER - GamePhysics.BTC_STACK_OVERLAP)
    return Obstacle(x = x, width = GamePhysics.BTC_COIN_DIAMETER, height = h, stackCount = stackCount)
}

// ── Initial world generation ──────────────────────────────────────────────────

private fun generateInitialState(): GameState {
    val coins = buildList {
        var x = 300f
        repeat(3) {
            addAll(generateCoinPattern(x))
            x += 400f + Random.nextFloat() * 200f
        }
    }
    val obstacles = buildList {
        var x = 800f
        repeat(4) {
            add(makeObstacle(x))
            x += 450f + Random.nextFloat() * 400f
        }
    }
    return GameState(coins = coins, obstacles = obstacles)
}

// ── Spawning helpers ──────────────────────────────────────────────────────────

/** Spawn a coin pattern when the world ahead is getting sparse. */
private fun maybeSpawnCoins(state: GameState): GameState {
    val horizon = state.scrollOffset + 900f
    val furthestCoin = state.coins.maxOfOrNull { it.x } ?: state.scrollOffset
    if (furthestCoin > horizon) return state

    // Gap before next pattern — varies to keep rhythm interesting
    val gap = 150f + Random.nextFloat() * 200f
    val newCoins = generateCoinPattern(furthestCoin + gap)
    return state.copy(coins = state.coins + newCoins)
}

/** Spawn obstacles with varied spacing — sometimes clustered, sometimes sparse. */
private fun maybeSpawnObstacles(state: GameState): GameState {
    val horizon = state.scrollOffset + 1000f
    val furthestObs = state.obstacles.maxOfOrNull { it.x } ?: state.scrollOffset
    if (furthestObs > horizon) return state

    // 40% chance to skip — creates natural gaps
    if (Random.nextFloat() > 0.6f) return state

    // Minimum gap must be clearable: character needs time to land + jump again.
    // At 1.8x sprint (~360px/s), a 400px gap gives ~1.1s — enough to land and tap-jump.
    val spacing = when (Random.nextInt(3)) {
        0 -> 400f + Random.nextFloat() * 150f    // snug but fair
        1 -> 550f + Random.nextFloat() * 200f    // comfortable
        else -> 750f + Random.nextFloat() * 300f  // breather
    }

    val obsX = furthestObs + spacing
    return state.copy(obstacles = state.obstacles + makeObstacle(obsX))
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
    standalone: Boolean = false,
    onScoreSubmit: ((score: Int, distance: Int, coins: Int, livesRemaining: Int) -> Unit)? = null,
    onShowLeaderboard: (() -> Unit)? = null,
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

    // Shared pointer handler for both game canvas and touch zone
    val pointerMod = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                when (event.type) {
                    PointerEventType.Press -> {
                        gameState = gameState.copy(isHolding = true)
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

    Column(modifier = modifier.fillMaxWidth()) {
        // Game canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (standalone) 320.dp else 280.dp)
                .then(pointerMod)
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
            drawHearts(gameState)
            if (gameState.isGameOver) {
                drawGameOver(textMeasurer, gameState)
            }
        }

        if (gameState.isGameOver) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = { gameState = generateInitialState() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0066CC))
                ) {
                    Text("Play Again", color = Color.White)
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (onScoreSubmit != null) {
                    OutlinedButton(onClick = {
                        onScoreSubmit(
                            gameState.finalScore,
                            (gameState.scrollOffset / GamePhysics.DISTANCE_DIVISOR).toInt(),
                            gameState.score,
                            gameState.lives
                        )
                    }) {
                        Text("Submit Score", color = Color(0xFF4A9EFF))
                    }
                }
                if (onShowLeaderboard != null) {
                    TextButton(onClick = { onShowLeaderboard() }) {
                        Text("Leaderboard", color = Color(0xFF4A9EFF))
                    }
                }
            }
        }
        } // end game canvas Box

        // Touch zone — visually distinct area below the game for input
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (standalone) 60.dp else 48.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF050D1A),  // matches ground grid base
                            Color(0xFF0A1830)
                        )
                    )
                )
                .then(pointerMod),
            contentAlignment = Alignment.Center
        ) {
            // Subtle visual hint
            val holdingAlpha = if (gameState.isHolding) 0.6f else 0.2f
            Text(
                text = if (gameState.isHolding) "SPRINTING ▸▸" else "HOLD to sprint  ·  RELEASE to jump",
                color = Color(0xFF0066CC).copy(alpha = holdingAlpha),
                fontSize = 11.sp,
                fontWeight = if (gameState.isHolding) FontWeight.Bold else FontWeight.Normal
            )
        }
    } // end Column
}
