# DigiRunner Leaderboard, Lives, and Standalone Launch — Design Spec

## Overview

Add a life system (3 hearts), combined scoring (distance + coins), game-over screen, standalone launcher, and a cross-platform leaderboard tied to DigiScope identity. Leaderboard visible in the Android app AND on digiscope.me.

## 1. Life System (3 Hearts)

**GameState changes:**
```
lives: Int = 3
```

Use the existing `isGameOver: Boolean` field (currently unused).

**Mechanics:**
- Each run starts with 3 hearts
- BTC stack hit: -1 heart + existing stumble slowdown + existing -2 coins
- 0 hearts = game over (physics stops, game-over overlay shown)
- No health regeneration — keeps runs finite and scores meaningful

**HUD rendering:**
- 3 heart icons in top-left, below "DigiByte" branding
- Filled heart = alive (DGB blue `#0066CC`)
- Outline heart = lost (dark `#002352`)
- Pulse animation on heart loss

**Physics changes in `GamePhysics.update()`:**
- When `hitObstacle && !isGameOver`: decrement `lives`
- When `lives <= 0`: set `isGameOver = true`
- When `isGameOver`: return state unchanged (freeze game)

## 2. Scoring (Combined)

**Formula:** `(scrollOffset / 100).toInt() + score * 5`

- `scrollOffset / 100` = distance points (1 per 100px)
- `score * 5` = coin bonus (5 per DGB coin)
- Displayed as breakdown on game-over screen

**New GameState field:**
```
finalScore: Int = 0  // computed on game over
```

Set `finalScore` when `isGameOver` triggers, not on every frame.

## 3. Game Over Screen

**Composable overlay** rendered on top of the game Canvas when `isGameOver`:

- Dark semi-transparent backdrop (70% black)
- "GAME OVER" title in DGB blue
- Score breakdown:
  - Distance: X points
  - Coins: Y × 5 = Z points
  - **Total: N**
- **"Submit Score"** button — visible if `DigiScopeClient.isLoggedIn()`
  - On tap: POST to `/api/hub/digirunner/score`
  - Shows "Submitted!" confirmation or "Already submitted" if duplicate
- **"Play Again"** button — resets GameState to `generateInitialState()` with 3 lives
- **"Leaderboard"** button — navigates to leaderboard view
- If not logged in: "Log in with Digi-ID to submit scores" text (no submit button)

## 4. Standalone Launcher

### Wallet Screen
- "Play DigiRunner" button below sync overlay area
- Visible when `syncState` is `Complete` or `Idle` (game already shows during `Syncing`/`Rescanning`)
- Opens DigiRunnerGame in a full-screen composable (not a new activity)
- Back button / X returns to wallet

### Hub Tab
- "DigiRunner" card/section within HubScreen:
  - Play button (blue, prominent)
  - Personal best score (from local SharedPreferences + server)
  - Top 3 leaderboard preview (handle + score)
  - "View Full Leaderboard" link → leaderboard screen

### Navigation
- New route: `digirunner` — full-screen game with game-over/restart flow
- New route: `digirunner_leaderboard` — leaderboard screen
- Accessible from: wallet play button, Hub play button, game-over leaderboard button

## 5. Leaderboard API (DigiScope Backend)

### Database

**New table:** `digirunner_scores`
```sql
CREATE TABLE IF NOT EXISTS digirunner_scores (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    score INTEGER NOT NULL,
    distance INTEGER NOT NULL,
    coins INTEGER NOT NULL,
    lives_remaining INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_digirunner_scores_score ON digirunner_scores(score DESC);
CREATE INDEX idx_digirunner_scores_user ON digirunner_scores(user_id, score DESC);
```

### REST Endpoints (under `/api/hub/digirunner/`)

All require `requireAuth` middleware.

**POST `/api/hub/digirunner/score`**
- Body: `{ score, distance, coins, livesRemaining }`
- Validates: `score == (distance / 100) + (coins * 5)` (anti-cheat)
- Rate limit: 1 submission per 10 seconds per user
- Rejects scores where `distance` or `coins` are physically impossible (e.g., coins > distance/50)
- Returns: `{ submitted: true, rank: N }` or `{ error: "..." }`

**GET `/api/hub/digirunner/leaderboard?period=all|weekly|daily&limit=20`**
- Returns: `[{ rank, handle, score, distance, coins, createdAt }]`
- `period=all`: all-time top scores
- `period=weekly`: last 7 days
- `period=daily`: last 24 hours
- Default: `period=all`, `limit=20`

**GET `/api/hub/digirunner/me`**
- Returns: `{ bestScore, bestRank, totalGames, totalCoins }`
- `bestScore`: user's highest score ever
- `bestRank`: rank among all players
- `totalGames`: number of submitted scores
- `totalCoins`: sum of all coins collected across all games

### Backend Files
- Controller: `controllers/digirunner.js`
- Migration: `migrations/076_digirunner_scores.js`
- Mount: `app.use("/api/hub/digirunner", requireAuth, digirunnerRouter)`

## 6. Website Leaderboard Page

**New page:** `/digirunner` on digiscope.me

- Top 20 all-time scores with DigiScope handles
- Period toggle: All Time / Weekly / Daily
- Each row: rank, handle, score, distance, coins, date
- Highlight current user's row (if logged in)
- Player's personal stats card (best score, rank, total games)
- "Download the Wallet" call-to-action linking to `/downloads/`
- Same API as the app: `GET /api/hub/digirunner/leaderboard`

**Frontend file:** `src/pages/DigiRunner.jsx`

## 7. DigiScopeClient (Wallet)

New methods in `DigiScopeClient.kt`:

```kotlin
suspend fun submitScore(score: Int, distance: Int, coins: Int, livesRemaining: Int): Boolean
suspend fun getLeaderboard(period: String = "all", limit: Int = 20): List<LeaderboardEntry>
suspend fun getMyStats(): DigiRunnerStats?
```

**New models in `HubModels.kt`:**
```kotlin
data class LeaderboardEntry(val rank: Int, val handle: String, val score: Int, val distance: Int, val coins: Int, val createdAt: Long)
data class DigiRunnerStats(val bestScore: Int, val bestRank: Int, val totalGames: Int, val totalCoins: Int)
```

## 8. Local High Score Persistence

Even without DigiScope login, persist the local best score in SharedPreferences (`dgb_sync_data` or a new `dgb_game` prefs file):
- `digirunner_best_score: Int`
- `digirunner_total_games: Int`
- Displayed in Hub section and game-over screen regardless of login state

## Files Modified

| File | Changes |
|------|---------|
| `game/.../GameState.kt` | Add `lives`, `finalScore` fields |
| `game/.../GamePhysics.kt` | Life decrement on hit, game-over freeze, score calculation |
| `game/.../GameRenderer.kt` | Hearts HUD, game-over overlay |
| `game/.../DigiRunnerGame.kt` | Game-over state handling, restart, standalone mode |
| `app/.../SyncOverlay.kt` | "Play DigiRunner" button when not syncing |
| `app/.../HubScreen.kt` | DigiRunner section with play/leaderboard/stats |
| `app/.../AppNavigation.kt` | New routes: `digirunner`, `digirunner_leaderboard` |
| `core/.../DigiScopeClient.kt` | New API methods: submitScore, getLeaderboard, getMyStats |
| `core/.../HubModels.kt` | LeaderboardEntry, DigiRunnerStats data classes |
| **Backend:** `controllers/digirunner.js` | REST endpoints |
| **Backend:** `migrations/076_digirunner_scores.js` | Database table |
| **Backend:** `server.js` | Mount digirunner routes |
| **Website:** `src/pages/DigiRunner.jsx` | Leaderboard page |

## Testing

- Hearts decrement correctly (3 → 2 → 1 → 0 = game over)
- Game freezes on game over (no physics, no input)
- Score formula validated: `(distance/100) + (coins*5)`
- Submit button only visible when logged in
- API rejects invalid scores (formula mismatch, impossible ratios)
- Rate limiting works (1 per 10s)
- Leaderboard periods filter correctly (all/weekly/daily)
- Personal stats aggregate across games
- Play button appears on wallet screen when not syncing
- Hub section shows top 3 + personal best
- Website leaderboard loads and displays correctly
