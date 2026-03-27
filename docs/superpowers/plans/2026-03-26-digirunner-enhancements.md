# DigiRunner Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform DigiRunner from a tap-to-jump runner into a sprint/crouch/jump game with a Digi-Robot character, 3D spinning DGB coins, and Bitcoin stack obstacles.

**Architecture:** All changes are in the 4-file `game` module. GameState gets new fields, GamePhysics gets sprint/crouch/charged-jump logic, GameRenderer gets new draw functions (robot, 3D coins, BTC stacks), DigiRunnerGame gets press/release input. Each task builds on the previous — data model first, then physics, then rendering, then input wiring.

**Tech Stack:** Kotlin, Jetpack Compose Canvas API, `pointerInput` with `awaitPointerEventScope`

**Spec:** `docs/superpowers/specs/2026-03-26-digirunner-enhancements-design.md`

---

### Task 1: Update Data Models (GameState)

**Files:**
- Modify: `game/src/main/java/io/digibyte/game/GameState.kt`

- [ ] **Step 1: Add new fields to GameState**

```kotlin
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
    val crouchAmount: Float = 0f
)
```

- [ ] **Step 2: Add rotationAngle to Coin**

```kotlin
data class Coin(val x: Float, val y: Float, val collected: Boolean = false, val rotationAngle: Float = 0f)
```

- [ ] **Step 3: Add stackCount to Obstacle**

```kotlin
data class Obstacle(val x: Float, val width: Float, val height: Float, val hit: Boolean = false, val stackCount: Int = 1)
```

- [ ] **Step 4: Verify build**

Run: `cd /home/polloloco/digibytewallet-android && ./gradlew :game:compileMainnetDebugKotlin`
Expected: BUILD SUCCESSFUL (data classes are compatible — new fields have defaults)

- [ ] **Step 5: Commit**

```bash
git add game/src/main/java/io/digibyte/game/GameState.kt
git commit -m "feat(game): add sprint/crouch/rotation/stack fields to game state"
```

---

### Task 2: Update Physics Constants and Size Increase

**Files:**
- Modify: `game/src/main/java/io/digibyte/game/GamePhysics.kt` (lines 3-13, constants block)

- [ ] **Step 1: Update constants**

Replace **only the constants** inside `GamePhysics` (lines 4-13). Keep the `update()` and `jump()` functions unchanged for now:

```kotlin
    // Replace lines 4-13 with:
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
```

- [ ] **Step 2: Verify build**

Run: `./gradlew :game:compileMainnetDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add game/src/main/java/io/digibyte/game/GamePhysics.kt
git commit -m "feat(game): update physics constants for 20% size increase and sprint params"
```

---

### Task 3: Implement Sprint, Crouch, and Charged Jump Physics

**Files:**
- Modify: `game/src/main/java/io/digibyte/game/GamePhysics.kt` (replace `update()` and `jump()`)

- [ ] **Step 1: Replace the `update()` function**

Replace the entire `fun update(...)` with:

```kotlin
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
```

- [ ] **Step 2: Add `chargedJump()` alongside existing `jump()`**

Keep the old `jump()` function — it's still called by `DigiRunnerGame.kt` until Task 5 rewires the input. Add the new function after it:

```kotlin
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
```

- [ ] **Step 3: Close the object (ensure trailing `}`)**

Make sure the `GamePhysics` object closing brace is present after the new `chargedJump()`.

- [ ] **Step 4: Verify build**

Run: `./gradlew :game:compileMainnetDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add game/src/main/java/io/digibyte/game/GamePhysics.kt
git commit -m "feat(game): sprint ramp, crouch, charged jump, coin rotation, stumble-breaks-sprint"
```

---

### Task 4: Update Spawners for BTC Stacks

**Files:**
- Modify: `game/src/main/java/io/digibyte/game/DigiRunnerGame.kt` (functions `generateInitialState()` and `maybeSpawnObstacles()`)

- [ ] **Step 1: Update `generateInitialState()`**

Update coin spawning to include random rotation angles, and obstacle spawning to use BTC stack model:

```kotlin
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
```

- [ ] **Step 2: Update `maybeSpawnCoins()` for rotation angle**

In the `add(Coin(...))` call inside `maybeSpawnCoins`, add `rotationAngle = Random.nextFloat() * 6.28f`.

- [ ] **Step 3: Update `maybeSpawnObstacles()` for BTC stacks**

```kotlin
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
```

- [ ] **Step 4: Verify build**

Run: `./gradlew :game:compileMainnetDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add game/src/main/java/io/digibyte/game/DigiRunnerGame.kt
git commit -m "feat(game): BTC stack spawning and coin rotation angles"
```

---

### Task 5: Wire Press/Release Input in DigiRunnerGame

**Files:**
- Modify: `game/src/main/java/io/digibyte/game/DigiRunnerGame.kt` (composable input + game loop)

- [ ] **Step 1: Add pointer event imports**

Add to imports:

```kotlin
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
```

Remove the `detectTapGestures` import if present:
```kotlin
// Remove: import androidx.compose.foundation.gestures.detectTapGestures
```

- [ ] **Step 2: Replace `detectTapGestures` with press/release handling**

In the `Box` modifier, replace:

```kotlin
.pointerInput(Unit) {
    detectTapGestures {
        gameState = GamePhysics.jump(gameState)
    }
}
```

With:

```kotlin
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
```

- [ ] **Step 3: Add DisposableEffect for lifecycle safety**

Inside the `Box` composable (before the `Canvas`), add:

```kotlin
DisposableEffect(Unit) {
    onDispose {
        gameState = gameState.copy(isHolding = false, holdDuration = 0f)
    }
}
```

Add the import: `import androidx.compose.runtime.DisposableEffect`

- [ ] **Step 4: Verify build**

Run: `./gradlew :game:compileMainnetDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add game/src/main/java/io/digibyte/game/DigiRunnerGame.kt
git commit -m "feat(game): press/release input for sprint + charged jump"
```

---

### Task 6: Update Brand Colors in GameRenderer

**Files:**
- Modify: `game/src/main/java/io/digibyte/game/GameRenderer.kt` (lines 34-47, color constants)

- [ ] **Step 1: Update color constants to official DigiByte brand**

Replace:

```kotlin
private val DgbBlue     = Color(0xFF002FD7)
private val DgbLight    = Color(0xFF4A7DFF)
private val DgbCoinEdge = Color(0xFF001A80)
```

With:

```kotlin
private val DgbBlue     = Color(0xFF0066CC)   // official DigiByte blue
private val DgbLight    = Color(0xFF4A9EFF)   // light accent
private val DgbDark     = Color(0xFF002352)   // official dark navy
```

Also add BTC colors after the existing obstacle colors:

```kotlin
private val BtcOrange   = Color(0xFFF7931A)   // Bitcoin orange
private val BtcDark     = Color(0xFFC16800)   // dark Bitcoin edge
private val BtcLight    = Color(0xFFFFB347)   // light Bitcoin highlight
```

Update references from `DgbCoinEdge` to `DgbDark` throughout the file.

- [ ] **Step 2: Verify build**

Run: `./gradlew :game:compileMainnetDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add game/src/main/java/io/digibyte/game/GameRenderer.kt
git commit -m "feat(game): official DigiByte brand colors + BTC orange palette"
```

---

### Task 7: Implement Digi-Robot Character Renderer

**Files:**
- Modify: `game/src/main/java/io/digibyte/game/GameRenderer.kt` (replace `drawCharacter()`)

- [ ] **Step 1: Replace `drawCharacter()` with `drawDigiRobot()`**

Delete the entire existing `fun DrawScope.drawCharacter(state: GameState)` function and replace with a new `drawDigiRobot()` function that renders:

1. Ground glow shadow (blue-tinted ellipse)
2. Boxy metal feet with chrome gradient
3. Piston legs with shine highlights — length scales with `crouchAmount`
4. Glowing blue knee joints
5. Chrome torso (linear gradient dark→light→dark) with `scale(1+crouch*0.1, 1-crouch*0.2)` transform
6. DGB logo circle on chest (`DgbBlue` fill, white "D" text)
7. Energy seam line across torso
8. Angular chrome arms with running swing animation (phase from `scrollOffset`)
9. Glowing blue shoulder joints
10. Chrome head (rounded rect with gradient)
11. Blue LED visor strip with glow (`shadowBlur`). Brighter when `sprintMultiplier > 1.05`. Red flicker when stumbling.
12. Antenna with `DgbBlue` beacon dot and glow
13. Sprint glow aura behind character when `sprintMultiplier > 1.05` (`rgba(0,170,255, (sprintMult-1)*0.5)`)
14. Same blink-disappear effect during stumble (existing pattern)

All rendering uses `GamePhysics.CHARACTER_SIZE` (48f) for proportions. The `crouchAmount` from `GameState` controls vertical compression via `DrawScope.scale()`.

- [ ] **Step 2: Update `drawCharacter` call to `drawDigiRobot`**

In `DigiRunnerGame.kt`, the Canvas block calls `drawCharacter(gameState)`. Update to `drawDigiRobot(gameState)`.

- [ ] **Step 3: Verify build**

Run: `./gradlew :game:compileMainnetDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add game/src/main/java/io/digibyte/game/GameRenderer.kt game/src/main/java/io/digibyte/game/DigiRunnerGame.kt
git commit -m "feat(game): Digi-Robot character with chrome body, LED visor, piston legs"
```

---

### Task 8: Implement 3D Spinning DGB Coins Renderer

**Files:**
- Modify: `game/src/main/java/io/digibyte/game/GameRenderer.kt` (replace `drawCoins()`)

- [ ] **Step 1: Replace `drawCoins()` with 3D rotation version**

Delete existing `drawCoins()` and replace with new version that:

1. Gets `cos(coin.rotationAngle)` and `abs(cos)` for each visible coin
2. Calculates render width: `COIN_SIZE * max(absCos, 0.15f)`
3. When `absCos < 0.15`: draws thin edge rectangle (4px wide, `DgbDark`)
4. When `cos > 0` (front face): draws ellipse in `DgbBlue`, inner ring stroke in `DgbLight`, white "D" letter scaled by `absCos`
5. When `cos < 0` (back face): draws ellipse in `DgbDark`
6. Adds highlight glint ellipse that shifts with rotation
7. Keeps the glow circle behind coin

Use `drawOval()` or `drawArc()` for the elliptical coin body. The "D" letter can use `drawText()` with the existing `textMeasurer` or the simple rect+arc approach scaled by `absCos`.

- [ ] **Step 2: Verify build**

Run: `./gradlew :game:compileMainnetDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add game/src/main/java/io/digibyte/game/GameRenderer.kt
git commit -m "feat(game): 3D Y-axis spinning DGB coins with official brand colors"
```

---

### Task 9: Implement BTC Coin Stack Obstacles Renderer

**Files:**
- Modify: `game/src/main/java/io/digibyte/game/GameRenderer.kt` (replace `drawObstacles()`)

- [ ] **Step 1: Replace `drawObstacles()` with `drawBTCStacks()`**

Delete existing `drawObstacles()` and replace with new version that:

For each obstacle, uses `obs.stackCount` to draw a vertical stack of Bitcoin coins:

1. Loop `i` from 0 to `stackCount - 1`
2. Each coin center Y: `groundY - BTC_COIN_DIAMETER/2 - i * (BTC_COIN_DIAMETER - BTC_STACK_OVERLAP)`
3. Slight horizontal offset: `±2px` for odd/even `i`
4. Orange glow circle behind each coin (`BtcOrange` with alpha 0.15)
5. Coin body: `drawCircle` with `BtcOrange` (top coin) or `BtcDark` (lower coins)
6. Edge ring: `drawCircle` stroke with `BtcLight` (top) or `BtcOrange` (lower)
7. "₿" text on top coin only (white, bold, centered)
8. Shadow ellipse between coins (dark, semi-transparent)

- [ ] **Step 2: Update `drawObstacles` call to `drawBTCStacks`**

In `DigiRunnerGame.kt`, update the Canvas block from `drawObstacles(gameState)` to `drawBTCStacks(gameState)`.

- [ ] **Step 3: Remove dead color constants**

Delete these unused constants from `GameRenderer.kt` (the old character and obstacle colors):
- `CharHead`, `CharBody`, `CharLegs`, `CharStumble` (replaced by chrome gradients in `drawDigiRobot`)
- `ObsBody`, `ObsStripe`, `ObsGlow` (replaced by BTC palette)

Note: `CharStumble` color (`#FF4444`) is still used for the visor stumble flicker in `drawDigiRobot()` — keep it but rename to `StumbleRed` for clarity, or inline the color.

- [ ] **Step 4: Verify build**

Run: `./gradlew :game:compileMainnetDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add game/src/main/java/io/digibyte/game/GameRenderer.kt game/src/main/java/io/digibyte/game/DigiRunnerGame.kt
git commit -m "feat(game): BTC coin stack obstacles with orange Bitcoin branding"
```

---

### Task 10: Add Sprint HUD Indicator

**Files:**
- Modify: `game/src/main/java/io/digibyte/game/GameRenderer.kt` (extend `drawScore()`)

- [ ] **Step 1: Add sprint bar to HUD**

Rename `drawScore()` to `drawHud()`, change parameter from `score: Int` to `state: GameState`. Keep all existing branding and score rendering (replace `score` with `state.score`), then append the sprint bar after it:

```kotlin
fun DrawScope.drawHud(textMeasurer: TextMeasurer, state: GameState) {
    // ── Existing branding (unchanged) ──
    val brandStyle = TextStyle(
        color = DgbLight.copy(alpha = 0.7f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold
    )
    drawText(textMeasurer = textMeasurer, text = "DigiByte", topLeft = Offset(10f, 8f), style = brandStyle)

    // ── Existing score (unchanged, uses state.score) ──
    val scoreText = "DGB: ${state.score}"
    val scoreStyle = TextStyle(color = ScoreColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    val measured = textMeasurer.measure(scoreText, scoreStyle)
    drawText(textMeasurer = textMeasurer, text = scoreText,
        topLeft = Offset(size.width - measured.size.width - 12f, 10f), style = scoreStyle)

    // Sprint charge bar (visible when holding or sprint active)
    if (state.sprintMultiplier > 1.02f || state.isHolding) {
        val barW = 60f
        val barH = 6f
        val barX = 10f
        val barY = 26f
        val fill = ((state.sprintMultiplier - 1f) / (GamePhysics.SPRINT_MAX_MULT - 1f)).coerceIn(0f, 1f)

        // Background
        drawRect(
            color = Color.Black.copy(alpha = 0.4f),
            topLeft = Offset(barX, barY),
            size = Size(barW, barH)
        )
        // Fill
        drawRect(
            color = Color(0xFF00AAFF),
            topLeft = Offset(barX, barY),
            size = Size(barW * fill, barH)
        )
        // Label
        val label = if (fill >= 0.99f) "MAX" else "SPRINT"
        drawText(
            textMeasurer = textMeasurer,
            text = label,
            topLeft = Offset(barX, barY + barH + 2f),
            style = TextStyle(color = Color(0xFF00AAFF), fontSize = 9.sp)
        )
    }
}
```

- [ ] **Step 2: Update Canvas call**

In `DigiRunnerGame.kt`, update `drawScore(textMeasurer, gameState.score)` to `drawHud(textMeasurer, gameState)`.

- [ ] **Step 3: Verify build**

Run: `./gradlew :game:compileMainnetDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add game/src/main/java/io/digibyte/game/GameRenderer.kt game/src/main/java/io/digibyte/game/DigiRunnerGame.kt
git commit -m "feat(game): sprint charge bar HUD indicator"
```

---

### Task 11: Build, Deploy, and Test on Device

**Files:**
- All 4 game files (already committed)

- [ ] **Step 1: Full build**

Run: `./gradlew :app:assembleMainnetDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Deploy to device**

Run: `adb install -r app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk`
Expected: Success

- [ ] **Step 3: Manual testing checklist**

Open the app during sync (or navigate to a screen showing DigiRunner):

- [ ] Digi-Robot renders with chrome body, blue visor, antenna at 48px
- [ ] Tap → small jump (0.6x). Hold 1s → big jump (1.2x)
- [ ] Hold → character crouches (pistons compress), speed ramps gently
- [ ] Release → spring jump, momentum carries
- [ ] DGB coins spin in 3D — "D" visible on front, dark back, thin edge
- [ ] Coins are 29px with official `#0066CC` blue
- [ ] BTC stacks: 1-3 orange coins, varying heights
- [ ] Triple stack requires charged jump to clear
- [ ] Hit BTC stack → stumble (0.8s slowdown), sprint broken
- [ ] Sprint bar appears in HUD when holding, fills to "MAX"
- [ ] 60fps on Samsung SM-N950U — no stuttering

- [ ] **Step 4: Fix any visual tuning issues**

Adjust sizes, timing, or colors based on on-device feel. Common tweaks:
- Sprint ramp too fast/slow → adjust `SPRINT_RAMP_TIME`
- Jump too high/low → adjust `JUMP_VELOCITY`, `JUMP_MIN_SCALE`, `JUMP_MAX_SCALE`
- Coins too fast/slow rotation → adjust `3.0f` in coin rotation update
- BTC stacks too close together → adjust spacing in `maybeSpawnObstacles()`

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "feat(game): DigiRunner v2 — Digi-Robot, 3D coins, BTC stacks, sprint mechanics"
```
