# DigiRunner Leaderboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 3-heart life system, combined scoring, game-over screen, standalone launcher, and cross-platform leaderboard tied to DigiScope identity.

**Architecture:** Game module gets lives + game-over (self-contained). Backend gets new digirunner controller + migration. App wires standalone launch routes + leaderboard UI. Website gets a leaderboard page. Co-developed: wallet endpoints aligned with backend from the start.

**Tech Stack:** Kotlin/Compose (game + app), Express/SQLite (backend), React (website)

**Spec:** `docs/superpowers/specs/2026-03-29-digirunner-leaderboard-design.md`

---

### Task 1: Add Lives and Game Over to GameState

**Files:**
- Modify: `game/src/main/java/io/digibyte/game/GameState.kt`

- [ ] **Step 1: Add lives and finalScore fields to GameState**

Add after the existing `crouchAmount` field:

```kotlin
    // Lives + game over
    val lives: Int = 3,
    val finalScore: Int = 0
```

The existing `isGameOver: Boolean = false` field is already present.

- [ ] **Step 2: Verify build**

Run: `cd /home/polloloco/digibytewallet-android && ./gradlew :game:compileMainnetDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add game/src/main/java/io/digibyte/game/GameState.kt
git commit -m "feat(game): add lives and finalScore to GameState"
```

---

### Task 2: Implement Life System in GamePhysics

**Files:**
- Modify: `game/src/main/java/io/digibyte/game/GamePhysics.kt`

- [ ] **Step 1: Add scoring constants**

After the BTC stack constants, add:

```kotlin
    // Scoring
    const val COIN_SCORE_MULT = 5     // each coin worth 5 points in final score
    const val DISTANCE_DIVISOR = 100f // 1 point per 100px traveled
```

- [ ] **Step 2: Add game-over freeze at top of update()**

As the very first line inside `fun update(...)`:

```kotlin
        if (state.isGameOver) return state
```

- [ ] **Step 3: Modify obstacle hit to decrement lives**

In the "Sprint broken on hit" section, change:

```kotlin
        val finalStumble = if (hitObstacle) STUMBLE_DURATION else newStumble
        val finalHolding = if (hitObstacle) false else state.isHolding
        val finalHoldDur = if (hitObstacle) 0f else newHoldDuration
        val finalSprint = if (hitObstacle) 1f else newSprintMult
        val finalCrouch = if (hitObstacle) 0f else newCrouch
```

To:

```kotlin
        val newLives = if (hitObstacle) state.lives - 1 else state.lives
        val gameOver = newLives <= 0
        val finalStumble = if (hitObstacle && !gameOver) STUMBLE_DURATION else newStumble
        val finalHolding = if (hitObstacle) false else state.isHolding
        val finalHoldDur = if (hitObstacle) 0f else newHoldDuration
        val finalSprint = if (hitObstacle) 1f else newSprintMult
        val finalCrouch = if (hitObstacle) 0f else newCrouch
```

- [ ] **Step 4: Compute finalScore on game over**

After the cull section, before `return state.copy(...)`:

```kotlin
        val computedFinalScore = if (gameOver) {
            (newScroll / DISTANCE_DIVISOR).toInt() + (state.score + newlyCollected) * COIN_SCORE_MULT
        } else 0
```

- [ ] **Step 5: Add new fields to return state.copy**

Add these lines to the return:

```kotlin
            lives = newLives,
            isGameOver = gameOver,
            finalScore = if (gameOver) computedFinalScore else state.finalScore
```

- [ ] **Step 6: Verify build**

Run: `./gradlew :game:compileMainnetDebugKotlin`

- [ ] **Step 7: Commit**

```bash
git add game/src/main/java/io/digibyte/game/GamePhysics.kt
git commit -m "feat(game): 3-heart life system with game-over freeze and combined scoring"
```

---

### Task 3: Render Hearts and Game Over Overlay

**Files:**
- Modify: `game/src/main/java/io/digibyte/game/GameRenderer.kt`

- [ ] **Step 1: Add drawHearts() function**

New `DrawScope` extension after `drawHud()`. Renders 3 heart icons at top-left (y around 48-50f, to the right of the sprint bar area):

- Loop 0..2, draw filled heart if `i < state.lives`, outline otherwise
- Each heart: two overlapping circles + a triangle forming a heart shape, or simplified as a `drawPath` heart
- Filled color: `DgbBlue` (`Color(0xFF0066CC)`)
- Outline color: `DgbDark` (`Color(0xFF002352)`)
- Heart size: ~12px wide, spacing: 16px apart
- Position: x starts at 80f, y = 10f (top area, right of branding)

- [ ] **Step 2: Add drawGameOver() function**

New `DrawScope` extension, takes `textMeasurer` and `state` params. Only renders content when `state.isGameOver`:

- Dark backdrop: `drawRect(Color.Black.copy(alpha = 0.7f))` over full canvas
- "GAME OVER" centered text: `DgbBlue`, 20.sp, bold
- Score breakdown below center:
  - "Distance: {(scrollOffset/100).toInt()}"
  - "Coins: {score} x 5 = {score*5}"
  - "TOTAL: {finalScore}" in larger text, `DgbLight` color
- All text via `drawText(textMeasurer, ...)`

- [ ] **Step 3: Verify build**

Run: `./gradlew :game:compileMainnetDebugKotlin`

- [ ] **Step 4: Commit**

```bash
git add game/src/main/java/io/digibyte/game/GameRenderer.kt
git commit -m "feat(game): hearts HUD and game-over overlay rendering"
```

---

### Task 4: Wire Game Over UI in DigiRunnerGame

**Files:**
- Modify: `game/src/main/java/io/digibyte/game/DigiRunnerGame.kt`

- [ ] **Step 1: Add callbacks to composable signature**

Change the function signature to:

```kotlin
@Composable
fun DigiRunnerGame(
    syncProgress: Float = 0f,
    standalone: Boolean = false,
    onScoreSubmit: ((score: Int, distance: Int, coins: Int, livesRemaining: Int) -> Unit)? = null,
    onShowLeaderboard: (() -> Unit)? = null,
    modifier: Modifier = Modifier
)
```

- [ ] **Step 2: Add drawHearts and drawGameOver calls in Canvas**

In the Canvas block, after `drawHud(textMeasurer, gameState)`:

```kotlin
            drawHearts(gameState)
            if (gameState.isGameOver) {
                drawGameOver(textMeasurer, gameState)
            }
```

- [ ] **Step 3: Add Compose button overlay for game over**

After the Canvas (inside the Box), add game-over buttons:

```kotlin
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
```

Add required imports: `Arrangement`, `Alignment`, `Button`, `ButtonDefaults`, `OutlinedButton`, `TextButton`, `Spacer`, `Column`, `padding`.

- [ ] **Step 4: Verify build**

Run: `./gradlew :game:compileMainnetDebugKotlin`

- [ ] **Step 5: Deploy and test locally**

Run: `./gradlew :app:assembleMainnetDebug && adb install -r app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk`

Test: play game, hit 3 BTC stacks, verify game-over screen shows with score breakdown and buttons.

- [ ] **Step 6: Commit**

```bash
git add game/src/main/java/io/digibyte/game/DigiRunnerGame.kt
git commit -m "feat(game): game-over UI with restart, score submit, and leaderboard callbacks"
```

---

### Task 5: Leaderboard Models and API Client

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/hub/HubModels.kt`
- Modify: `core/src/main/java/io/digibyte/core/digiscope/DigiScopeClient.kt`

- [ ] **Step 1: Add data classes to HubModels.kt**

At the end of the file:

```kotlin
data class LeaderboardEntry(
    val rank: Int,
    val handle: String,
    val score: Int,
    val distance: Int,
    val coins: Int,
    val createdAt: Long
)

data class DigiRunnerStats(
    val bestScore: Int,
    val bestRank: Int,
    val totalGames: Int,
    val totalCoins: Int
)
```

- [ ] **Step 2: Add submitDigiRunnerScore to DigiScopeClient**

```kotlin
    suspend fun submitDigiRunnerScore(score: Int, distance: Int, coins: Int, livesRemaining: Int): Boolean =
        withContext(Dispatchers.IO) {
            val token = jwtToken ?: return@withContext false
            try {
                val json = JSONObject().apply {
                    put("score", score)
                    put("distance", distance)
                    put("coins", coins)
                    put("livesRemaining", livesRemaining)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$BASE_URL/hub/digirunner/score")
                    .header("Authorization", "Bearer $token")
                    .post(body)
                    .build()
                client.newCall(request).execute().isSuccessful
            } catch (e: Exception) { false }
        }
```

- [ ] **Step 3: Add getDigiRunnerLeaderboard to DigiScopeClient**

```kotlin
    suspend fun getDigiRunnerLeaderboard(period: String = "all", limit: Int = 20): List<LeaderboardEntry> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/hub/digirunner/leaderboard?period=$period&limit=$limit")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext emptyList()
                val json = JSONObject(response.body?.string() ?: return@withContext emptyList())
                val arr = json.optJSONArray("leaderboard") ?: return@withContext emptyList()
                (0 until arr.length()).map { i ->
                    val e = arr.getJSONObject(i)
                    LeaderboardEntry(
                        rank = e.optInt("rank", i + 1),
                        handle = e.optString("handle", "Anonymous"),
                        score = e.getInt("score"),
                        distance = e.optInt("distance", 0),
                        coins = e.optInt("coins", 0),
                        createdAt = e.optLong("createdAt", 0)
                    )
                }
            } catch (e: Exception) { emptyList() }
        }
```

- [ ] **Step 4: Add getDigiRunnerStats to DigiScopeClient**

```kotlin
    suspend fun getDigiRunnerStats(): DigiRunnerStats? = withContext(Dispatchers.IO) {
        val token = jwtToken ?: return@withContext null
        try {
            val request = Request.Builder()
                .url("$BASE_URL/hub/digirunner/me")
                .header("Authorization", "Bearer $token")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val json = JSONObject(response.body?.string() ?: return@withContext null)
            DigiRunnerStats(
                bestScore = json.optInt("bestScore", 0),
                bestRank = json.optInt("bestRank", 0),
                totalGames = json.optInt("totalGames", 0),
                totalCoins = json.optInt("totalCoins", 0)
            )
        } catch (e: Exception) { null }
    }
```

- [ ] **Step 5: Verify build**

Run: `./gradlew :core:compileMainnetDebugKotlin`

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/io/digibyte/core/hub/HubModels.kt \
       core/src/main/java/io/digibyte/core/digiscope/DigiScopeClient.kt
git commit -m "feat: DigiRunner leaderboard API client + data models"
```

---

### Task 6: Backend — Migration and Controller

**Files (on VPS digiscope.me):**
- Create: `/opt/digiscope-backend/src/controllers/digirunner.js`
- Create: `/opt/digiscope-backend/src/models/migrations/076_digirunner_scores.js`
- Modify: `/opt/digiscope-backend/src/models/db.js` (add migration import)
- Modify: `/opt/digiscope-backend/src/server.js` (mount routes)

- [ ] **Step 1: Create migration file**

SSH to digiscope.me and create `076_digirunner_scores.js` with CREATE TABLE for `digirunner_scores` (id, user_id, score, distance, coins, lives_remaining, created_at) + indexes on score DESC and user_id.

- [ ] **Step 2: Create controller**

`digirunner.js` Express Router with:
- `POST /score` — validate `score === Math.floor(distance/100) + coins*5`, rate limit 10s, insert, return rank
- `GET /leaderboard` — query with period filter, join admin_users for COALESCE(custom_username, username) as handle
- `GET /me` — user's best score, rank (COUNT of higher scores + 1), total games, total coins

- [ ] **Step 3: Wire migration and mount routes**

Add migration import to db.js after migration 075.
Add `import digirunnerRouter from './controllers/digirunner.js'` and `app.use("/api/hub/digirunner", requireAuth, digirunnerRouter)` to server.js.

- [ ] **Step 4: Restart and test**

```bash
pm2 restart digiscope-backend --update-env
curl -s https://api.digiscope.me/api/hub/digirunner/leaderboard -H "Authorization: Bearer $TOKEN"
curl -s https://api.digiscope.me/api/hub/digirunner/me -H "Authorization: Bearer $TOKEN"
```

---

### Task 7: Standalone Launcher and Hub Section

**Files:**
- Modify: `app/src/main/java/io/digibyte/ui/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/io/digibyte/ui/sync/SyncOverlay.kt`
- Modify: `app/src/main/java/io/digibyte/ui/hub/HubScreen.kt`

- [ ] **Step 1: Add digirunner and digirunner_leaderboard routes to AppNavigation**

Two new `composable()` entries:
- `"digirunner"` — DigiRunnerGame with `standalone=true`, callbacks wired to submit score via DigiScopeClient and navigate to leaderboard
- `"digirunner_leaderboard"` — the leaderboard screen (Task 8)

- [ ] **Step 2: Add Play button to SyncOverlay when sync is complete**

When `syncState` is `Complete` or `Idle`, show "Play DigiRunner" button. Requires an `onPlayGame` callback parameter added to `SyncOverlay`.

- [ ] **Step 3: Add DigiRunner section to HubScreen**

Card in Hub with: Play button, personal best, top 3 preview, "View Leaderboard" link. Fetch data via DigiScopeClient in a LaunchedEffect.

- [ ] **Step 4: Verify build and commit**

```bash
./gradlew :app:assembleMainnetDebug
git add app/src/main/java/io/digibyte/ui/navigation/AppNavigation.kt \
       app/src/main/java/io/digibyte/ui/sync/SyncOverlay.kt \
       app/src/main/java/io/digibyte/ui/hub/HubScreen.kt
git commit -m "feat: standalone DigiRunner launcher in wallet + Hub leaderboard section"
```

---

### Task 8: Leaderboard Screen

**Files:**
- Create: `app/src/main/java/io/digibyte/ui/hub/DigiRunnerLeaderboardScreen.kt`

- [ ] **Step 1: Create leaderboard composable**

Full-screen with:
- Period toggle chips (All Time / Weekly / Daily)
- LazyColumn of LeaderboardEntry rows (rank, handle, score, coins, distance)
- Personal stats card at top
- Loading/error states
- Back navigation

- [ ] **Step 2: Verify build and commit**

```bash
./gradlew :app:assembleMainnetDebug
git add app/src/main/java/io/digibyte/ui/hub/DigiRunnerLeaderboardScreen.kt
git commit -m "feat: DigiRunner leaderboard screen with period filtering"
```

---

### Task 9: Website Leaderboard Page

**Files (on VPS):**
- Create: `/opt/digibyte-compendium/src/pages/DigiRunnerLeaderboard.jsx`
- Modify: `/opt/digibyte-compendium/src/App.jsx` (add route)

- [ ] **Step 1: Create React page**

`/digirunner` route showing top 20 scores, period toggle, current user highlight, personal stats, download CTA.

- [ ] **Step 2: Add route, build, deploy**

Add route to App.jsx, build, deploy to `/var/www/digiscope/`.

---

### Task 10: Build, Deploy, and End-to-End Test

- [ ] **Step 1: Full build and deploy**

```bash
./gradlew :app:assembleMainnetDebug
adb install -r app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk
```

- [ ] **Step 2: Test checklist**

- Play from Hub → lose 3 hearts → game over with score
- Submit score → appears in leaderboard
- Play Again → fresh 3 hearts
- View leaderboard in app → period filtering works
- Check digiscope.me/digirunner → same scores
- Play from wallet screen → standalone works
- Play without login → no submit button, game still works

- [ ] **Step 3: Final commit**

```bash
git commit -m "feat: DigiRunner leaderboard — lives, scoring, standalone, cross-platform"
```
