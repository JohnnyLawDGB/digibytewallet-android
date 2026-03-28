# DigiRunner Mini-Game Enhancements — Design Spec

## Overview

Five enhancements to the DigiRunner side-scrolling mini-game that plays during blockchain sync. The changes transform the game from a simple tap-to-jump runner into a more polished, branded experience with richer mechanics.

## 1. Digi-Robot Character

Replace the current human runner with a chrome robot.

**Visual design:**
- Chrome metallic body with linear gradient shading (dark edges, bright center) for a 3D metal look
- Blue LED visor strip across the head — glows brighter during sprint
- DGB logo circle on chest (`#0066CC` background, white "D")
- Glowing blue joint nodes at shoulders and knees (`#0066CC`)
- Piston-style legs that visually compress during crouch
- Small antenna on top of head with `#0066CC` beacon dot (subtle glow)
- Angular chrome arms with rounded fist endpoints

**State-dependent rendering:**
- **Running:** Piston legs alternate (same leg-swing animation, adapted to pistons). Arms swing. Visor steady glow.
- **Sprinting:** Visor brightens. Sprint glow aura behind character (`rgba(0,170,255,alpha)` scaled to sprint multiplier). Leg animation speeds up.
- **Crouching (hold):** Body compresses vertically ~20% via `scale(stretchX, squashY)`. Pistons shorten. Head lowers toward torso.
- **Jumping:** Legs extend straight. Arms angle upward. Sprint glow trails if momentum carrying.
- **Stumbling:** Blink effect (same as current). Visor flickers red.

**Size:** 48px tall (up from 40px). Same hitbox proportions: `charLeft = CHAR_SCREEN_X - CHARACTER_SIZE * 0.25f`, `charRight = CHAR_SCREEN_X + CHARACTER_SIZE * 0.25f`.

## 2. Sprint + Crouch + Spring Jump Input

Replace `detectTapGestures` with press/release tracking.

**Input handling:**
- Use Compose `pointerInput` with `awaitPointerEventScope` to detect press, hold, and release events
- On pointer down: set `isHolding = true`, start accumulating `holdDuration`
- On pointer up: fire jump with charged velocity, set `isHolding = false`, reset `holdDuration`

**New GameState fields:**
```
isHolding: Boolean = false
holdDuration: Float = 0f        // seconds held so far
sprintMultiplier: Float = 1.0f  // current speed multiplier
crouchAmount: Float = 0f        // 0.0 = standing, 1.0 = fully crouched
```

**Sprint ramp (gentle):**
- `sprintMultiplier` increases from 1.0 to 1.3 over 0.8 seconds while holding
- Formula: `sprintMultiplier = 1.0 + min(holdDuration / 0.8, 1.0) * 0.3`
- On release: decays back to 1.0 over 0.5 seconds (`sprintMultiplier -= delta * 0.6`, clamped to 1.0)
- Scroll speed: `SCROLL_SPEED * (1 + syncProgress * 0.5) * sprintMultiplier * stumbleMultiplier`

**Crouch animation:**
- `crouchAmount` ramps from 0 to 1 over 0.4 seconds while holding
- On release: snaps back to 0 over 0.2 seconds (`crouchAmount -= delta * 5`)
- Applied in renderer as `scale(1 + crouchAmount * 0.1, 1 - crouchAmount * 0.2)` on the character

**Charged jump:**
- Jump velocity scales with hold duration
- `jumpScale = min(holdDuration / 0.8, 1.0) * 0.6 + 0.6` (range: 0.6x for tap to 1.2x for full charge)
- `characterVelocity = JUMP_VELOCITY * jumpScale`
- Only fires if character is on ground (`characterY <= GROUND_Y + 1f`)

**Momentum carry:**
- After release, `sprintMultiplier` decays gradually (0.6/sec) rather than resetting instantly
- No re-sprint while airborne — `isHolding` can only activate sprint when on ground

**State accumulation:**
- `holdDuration` is incremented by `deltaTime` inside `GamePhysics.update()` when `isHolding` is true (not in the pointer handler — frame-synced timing)
- `sprintMultiplier` and `crouchAmount` are derived from `holdDuration` in `update()`

**Sprint vs stumble interaction:**
- On obstacle hit, `isHolding` is forced to `false`, `sprintMultiplier` resets to 1.0, `holdDuration` resets to 0. Getting hit breaks the sprint — player must re-press after stumble ends.

**Lifecycle safety:**
- `isHolding` is reset to `false` on `DisposableEffect(onDispose)` in the composable
- The existing `deltaTime.coerceAtMost(0.05f)` cap prevents physics explosion if a frame takes too long after resume

**Crouch hitbox:**
- Crouch is visual-only — the collision box stays at full `CHARACTER_SIZE` height. Crouch is for charging the jump, not for dodging under obstacles.

## 3. Entity Size Increase (+20%)

**GamePhysics constants:**
| Constant | Old | New |
|----------|-----|-----|
| `CHARACTER_SIZE` | 40f | 48f |
| `COIN_SIZE` | 24f | 29f |
| `COIN_COLLECT_RADIUS` | 30f | 36f |
| `CHAR_SCREEN_X` | 60f | 80f |

**Obstacle sizing:** Replaced by BTC stacks (Section 5). All stacks use fixed `width = 36f` (coin diameter). Heights determined by `stackCount` (see Section 5).

**Jump velocity unchanged** at 600f — bigger character with same jump height creates a tighter feel that complements the sprint mechanic.

## 4. 3D Y-Axis Spinning DGB Coins

Each coin gets a rotation angle that increments per frame.

**New Coin field:**
```
data class Coin(val x: Float, val y: Float, val collected: Boolean = false, val rotationAngle: Float = 0f)
```

**Physics update:**
- Rotation is updated inside the existing `coins.map {}` block in `GamePhysics.update()` via `coin.copy(rotationAngle = coin.rotationAngle + 3.0f * deltaTime)` alongside the collection check (immutable data class — no mutation)
- 3.0 radians/sec = ~0.5 rotations/sec
- Initial angle randomized per coin on spawn

**Rendering:**
- Render width = `COIN_SIZE * max(abs(cos(angle)), 0.15f)` — never fully invisible at edge
- `cos(angle) > 0`: front face — `#0066CC` fill, `#4A9EFF` inner ring stroke, white "D" letter
- `cos(angle) < 0`: back face — `#002352` fill (dark navy)
- `abs(cos(angle)) < 0.15`: edge view — thin `#002352` rectangle (4px wide)
- Highlight glint: small ellipse at top-left that shifts with rotation angle, `rgba(200,220,255, 0.3 * absCos)`
- Glow behind coin: `rgba(0,102,204, 0.25)` circle at `radius + 4`

**Brand colors (official DigiByte-Core/digibyte-logos):**
| Role | Hex | Replaces |
|------|-----|----------|
| Primary blue (outer ring) | `#0066CC` | `DgbBlue` (`#002FD7`) |
| Dark navy (inner circle) | `#002352` | `DgbCoinEdge` (`#001A80`) |
| Light accent | `#4A9EFF` | `DgbLight` (`#4A7DFF`) |
| Logo/text | `#FFFFFF` | unchanged |
| Coin highlight | `#AAC8FF` | `CoinShine` (unchanged) |

## 5. Bitcoin Coin Stack Obstacles

Replace red hazard blocks with stacked Bitcoin coins.

**New Obstacle model:**
```
data class Obstacle(val x: Float, val width: Float, val height: Float, val hit: Boolean = false, val stackCount: Int = 1)
```

**Spawning:**
- `stackCount` = random 1 to 3
- `coinDiameter = 36f`, `stackOverlap = 16f` (coins overlap vertically)
- Height formula: `coinDiameter + (stackCount - 1) * (coinDiameter - stackOverlap)`
  - Single: `36f` (easily jumpable)
  - Double: `56f` (needs a decent jump)
  - Triple: `76f` (needs charged jump to clear)
- Width: `36f` (coin diameter) for all stacks
- Both `generateInitialState()` and `maybeSpawnObstacles()` must be updated to use the new BTC stack model

**Rendering per coin in stack:**
- Orange fill: top coin = `#F7931A`, lower coins = `#C16800` (darker, shadowed)
- Edge ring: `#FFB347` (top) / `#F7931A` (lower)
- Bitcoin symbol "₿" in white on the top coin only
- Each coin slightly offset (±2px horizontal) for a natural "piled" look
- Orange glow behind stack: `rgba(247,147,26, 0.15)` per coin
- Shadow ellipse between stacked coins for depth

**Collision:**
- Same hitbox logic as current obstacles — character rect vs obstacle rect
- On hit: `stumbleTimer = STUMBLE_DURATION` (0.8s), `STUMBLE_SPEED_MULT = 0.3`
- BTC coins don't spin (static) — visual contrast with spinning DGB coins reinforces "collect blue, avoid orange"

## Files Modified

| File | Changes |
|------|---------|
| `game/.../GameState.kt` | Add `isHolding`, `holdDuration`, `sprintMultiplier`, `crouchAmount` to `GameState`. Add `rotationAngle` to `Coin`. Add `stackCount` to `Obstacle`. |
| `game/.../GamePhysics.kt` | New constants (sizes, sprint params). Update `update()` for sprint ramp, crouch, momentum decay, coin rotation, BTC stack collision. Replace `jump()` with charged jump. |
| `game/.../GameRenderer.kt` | New `drawDigiRobot()` replacing `drawCharacter()`. Update `drawCoins()` for 3D rotation. New `drawBTCStack()` replacing `drawObstacles()`. Update brand colors to official. Add sprint glow + HUD charge bar (60px wide bar below branding, fills with `#00AAFF` proportional to `holdDuration/0.8`, label "SPRINT" when active, "MAX" at full charge). |
| `game/.../DigiRunnerGame.kt` | Replace `detectTapGestures` with `awaitPointerEventScope` for press/release. Pass hold state to physics. Update spawn sizing. |

## Testing

- **Sprint feel:** Hold and verify speed ramp is gentle (not jarring), crouch animation is smooth
- **Jump scaling:** Quick tap = low jump, 1s hold = max jump. Verify triple BTC stack requires charged jump.
- **Coin rotation:** Smooth 3D illusion, "D" visible on front, dark on back, thin edge at 90°
- **BTC stacks:** 1-3 coins, correct heights, orange contrasts with blue, stumble on hit
- **Character:** Chrome shading reads well at 48px, visor glow visible, pistons compress on crouch
- **Performance:** 60fps on Samsung SM-N950U (API 28). Canvas draw calls stay reasonable.
