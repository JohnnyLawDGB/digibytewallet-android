package io.digibyte.game

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Colour palette — DigiByte cyber city aesthetic ──────────────────────────

private val SkyTop      = Color(0xFF050A1A)   // near-black top
private val SkyMid      = Color(0xFF0A1538)   // deep navy
private val SkyBot      = Color(0xFF102050)   // dark blue horizon

private val CityDark    = Color(0xFF0D1B3E)   // far buildings
private val CityMid     = Color(0xFF132850)   // mid buildings
private val CityLight   = Color(0xFF1A3568)   // near buildings
private val WindowGlow  = Color(0xFF2196F3)   // lit windows
private val WindowDim   = Color(0xFF0D47A1)   // dim windows

private val GridLine    = Color(0xFF0066CC)   // ground grid lines
private val GridBase    = Color(0xFF050D1A)   // ground base
private val GridGlow    = Color(0xFF00AAFF)   // grid intersection glow

private val DgbBlue     = Color(0xFF0066CC)   // official DigiByte blue
private val DgbLight    = Color(0xFF4A9EFF)   // light accent
private val DgbDark     = Color(0xFF002352)   // official dark navy
private val CoinShine   = Color(0xFFAAC8FF)   // coin highlight

private val CharHead    = Color(0xFFFFDBAC)   // skin tone
private val CharBody    = Color(0xFF0052CC)   // DGB blue shirt
private val CharLegs    = Color(0xFF1A1A2E)   // dark trousers
private val CharStumble = Color(0xFFFF4444)   // flash red on hit

private val ObsBody     = Color(0xFFCC2222)   // firewall red
private val ObsStripe   = Color(0xFFFFCC00)   // warning stripe
private val ObsGlow     = Color(0xFFFF4444)   // danger glow

private val BtcOrange   = Color(0xFFF7931A)   // Bitcoin orange
private val BtcDark     = Color(0xFFC16800)   // dark Bitcoin edge
private val BtcLight    = Color(0xFFFFB347)   // light Bitcoin highlight

private val ScoreColor  = Color(0xFF00CCFF)   // HUD text

// ─────────────────────────────────────────────────────────────────────────────

/** Draw the gradient sky with stars. */
fun DrawScope.drawBackground(scrollOffset: Float) {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(SkyTop, SkyMid, SkyBot),
            startY = 0f,
            endY = size.height * 0.65f
        )
    )
    // Twinkling stars
    drawStars(scrollOffset)
    // City skyline layers
    drawFarCity(scrollOffset)
    drawNearCity(scrollOffset)
}

/** Pixel stars — slow drift. */
private fun DrawScope.drawStars(scrollOffset: Float) {
    val starData = listOf(
        0.08f to 0.06f, 0.22f to 0.12f, 0.35f to 0.04f, 0.48f to 0.15f,
        0.55f to 0.08f, 0.68f to 0.03f, 0.75f to 0.11f, 0.82f to 0.07f,
        0.92f to 0.14f, 0.15f to 0.18f, 0.42f to 0.20f, 0.65f to 0.22f,
        0.03f to 0.25f, 0.30f to 0.02f, 0.58f to 0.19f, 0.88f to 0.10f
    )
    val drift = scrollOffset * 0.02f
    starData.forEach { (xr, yr) ->
        val x = ((xr * size.width + drift) % size.width + size.width) % size.width
        val y = yr * size.height * 0.5f
        val twinkle = (kotlin.math.sin((scrollOffset * 0.5f + xr * 100f).toDouble()) * 0.3f + 0.7f).toFloat()
        drawCircle(
            color = Color.White.copy(alpha = twinkle * 0.8f),
            radius = 1.5f,
            center = Offset(x, y)
        )
    }
}

/** Distant city skyline — slow parallax. */
private fun DrawScope.drawFarCity(scrollOffset: Float) {
    val horizonY = size.height * 0.55f
    val parallax = scrollOffset * 0.15f
    // Building widths and heights (as ratios of screen)
    val buildings = listOf(
        0.00f to 0.18f to 0.06f,  0.06f to 0.25f to 0.04f,
        0.10f to 0.14f to 0.05f,  0.15f to 0.30f to 0.03f,
        0.18f to 0.12f to 0.06f,  0.24f to 0.22f to 0.04f,
        0.28f to 0.28f to 0.03f,  0.31f to 0.16f to 0.05f,
        0.36f to 0.20f to 0.04f,  0.40f to 0.32f to 0.03f,
        0.43f to 0.15f to 0.06f,  0.49f to 0.26f to 0.04f,
        0.53f to 0.18f to 0.05f,  0.58f to 0.24f to 0.03f,
        0.61f to 0.20f to 0.06f,  0.67f to 0.28f to 0.04f,
        0.71f to 0.14f to 0.05f,  0.76f to 0.22f to 0.03f,
        0.79f to 0.30f to 0.06f,  0.85f to 0.16f to 0.04f,
        0.89f to 0.24f to 0.05f,  0.94f to 0.20f to 0.03f,
    )
    val w = size.width

    // Draw two copies for seamless wrapping
    for (copy in 0..1) {
        buildings.forEach { (posHeight, widthRatio) ->
            val (xr, hr) = posHeight
            val bx = (xr + copy) * w - (parallax % w)
            val bw = widthRatio * w
            val bh = hr * horizonY
            if (bx + bw > -50f && bx < w + 50f) {
                drawRect(
                    color = CityDark,
                    topLeft = Offset(bx, horizonY - bh),
                    size = Size(bw, bh)
                )
                // Random lit windows
                val winStep = 8f
                var wy = horizonY - bh + 6f
                while (wy < horizonY - 4f) {
                    var wx = bx + 3f
                    while (wx < bx + bw - 3f) {
                        val lit = ((wx * 7f + wy * 13f + scrollOffset * 0.01f).toInt() % 5) < 2
                        if (lit) {
                            drawRect(
                                color = WindowGlow.copy(alpha = 0.3f),
                                topLeft = Offset(wx, wy),
                                size = Size(3f, 3f)
                            )
                        }
                        wx += winStep
                    }
                    wy += winStep
                }
            }
        }
    }
}

/** Near city buildings — faster parallax. */
private fun DrawScope.drawNearCity(scrollOffset: Float) {
    val horizonY = size.height * 0.60f
    val parallax = scrollOffset * 0.35f
    val buildings = listOf(
        0.00f to 0.22f to 0.08f,  0.08f to 0.30f to 0.06f,
        0.14f to 0.18f to 0.07f,  0.21f to 0.35f to 0.05f,
        0.26f to 0.20f to 0.08f,  0.34f to 0.28f to 0.06f,
        0.40f to 0.24f to 0.07f,  0.47f to 0.32f to 0.05f,
        0.52f to 0.16f to 0.08f,  0.60f to 0.26f to 0.06f,
        0.66f to 0.20f to 0.07f,  0.73f to 0.34f to 0.05f,
        0.78f to 0.22f to 0.08f,  0.86f to 0.28f to 0.06f,
        0.92f to 0.18f to 0.08f,
    )
    val w = size.width

    for (copy in 0..1) {
        buildings.forEach { (posHeight, widthRatio) ->
            val (xr, hr) = posHeight
            val bx = (xr + copy) * w - (parallax % w)
            val bw = widthRatio * w
            val bh = hr * horizonY
            if (bx + bw > -50f && bx < w + 50f) {
                drawRect(
                    color = CityMid,
                    topLeft = Offset(bx, horizonY - bh),
                    size = Size(bw, bh)
                )
                // Lit windows — brighter on near buildings
                val winStep = 7f
                var wy = horizonY - bh + 5f
                while (wy < horizonY - 3f) {
                    var wx = bx + 3f
                    while (wx < bx + bw - 3f) {
                        val lit = ((wx * 11f + wy * 7f + scrollOffset * 0.02f).toInt() % 4) < 2
                        if (lit) {
                            drawRect(
                                color = WindowGlow.copy(alpha = 0.5f),
                                topLeft = Offset(wx, wy),
                                size = Size(3f, 4f)
                            )
                        }
                        wx += winStep
                    }
                    wy += winStep
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/** Digital grid ground — Tron-style. */
fun DrawScope.drawGround(scrollOffset: Float) {
    val groundTop = size.height * 0.75f
    val groundH = size.height - groundTop

    // Dark base
    drawRect(
        color = GridBase,
        topLeft = Offset(0f, groundTop),
        size = Size(size.width, groundH)
    )

    // Horizontal grid lines
    val hLineSpacing = 12f
    var hy = groundTop
    var lineIdx = 0
    while (hy < size.height) {
        val alpha = if (lineIdx == 0) 0.8f else 0.25f
        drawLine(
            color = GridLine.copy(alpha = alpha),
            start = Offset(0f, hy),
            end = Offset(size.width, hy),
            strokeWidth = if (lineIdx == 0) 2f else 1f
        )
        hy += hLineSpacing
        lineIdx++
    }

    // Vertical grid lines (scrolling)
    val vLineSpacing = 32f
    val vOffset = scrollOffset % vLineSpacing
    var vx = -vOffset
    while (vx < size.width) {
        drawLine(
            color = GridLine.copy(alpha = 0.3f),
            start = Offset(vx, groundTop),
            end = Offset(vx, size.height),
            strokeWidth = 1f
        )
        // Glow dot at top intersection
        drawCircle(
            color = GridGlow.copy(alpha = 0.4f),
            radius = 2f,
            center = Offset(vx, groundTop)
        )
        vx += vLineSpacing
    }

    // Top edge glow
    drawLine(
        color = GridGlow.copy(alpha = 0.6f),
        start = Offset(0f, groundTop),
        end = Offset(size.width, groundTop),
        strokeWidth = 2f
    )
}

// ─────────────────────────────────────────────────────────────────────────────

/** Ground Y in canvas coordinates. */
private fun DrawScope.groundYCanvas(): Float = size.height * 0.75f

/** Convert physics Y (up-positive, 0 = ground) to canvas Y (down-positive). */
private fun DrawScope.physToCanvas(physY: Float): Float =
    groundYCanvas() - physY

// ─────────────────────────────────────────────────────────────────────────────

/** Chrome gradient used for robot body, head, arms, feet. */
private val ChromeGradient = Brush.horizontalGradient(
    colors = listOf(
        Color(0xFF556677), Color(0xFFBBCCDD), Color(0xFFDDEEFF),
        Color(0xFFBBCCDD), Color(0xFF556677)
    )
)

/** Digi-Robot — chrome metallic robot character. */
fun DrawScope.drawDigiRobot(state: GameState) {
    val charX = GamePhysics.CHAR_SCREEN_X
    val canvasY = physToCanvas(state.characterY)
    val s = GamePhysics.CHARACTER_SIZE          // 48px tall
    val t = state.scrollOffset
    val isStumbling = state.stumbleTimer > 0f
    val crouch = state.crouchAmount
    val sprintMult = state.sprintMultiplier

    // ── 13. Stumble blink — skip render on blink frame ──
    if (isStumbling && ((state.stumbleTimer * 10f).toInt() % 2 == 0)) return

    // ── 12. Sprint glow — behind character ──
    if (sprintMult > 1.05f) {
        drawCircle(
            color = Color(0xFF00AAFF).copy(alpha = (sprintMult - 1f) * 0.5f),
            radius = s * 0.6f,
            center = Offset(charX, canvasY - s * 0.5f)
        )
    }

    // ── 1. Ground shadow ──
    drawOval(
        color = DgbBlue.copy(alpha = 0.2f),
        topLeft = Offset(charX - s * 0.4f, groundYCanvas() - 3f),
        size = Size(s * 0.8f, 6f)
    )

    // ── Running animation phase ──
    val legPhase = if (state.isJumping) 0f else (t * 0.02f) % (2f * Math.PI.toFloat())
    val legSwing = kotlin.math.sin(legPhase) * 6f
    val armSwing = kotlin.math.cos(legPhase) * 8f

    // ── Dimensional helpers ──
    val footW = s * 0.22f
    val footH = s * 0.10f
    val legW = s * 0.14f
    val baseLegH = s * 0.28f
    val legH = baseLegH * (1f - crouch * 0.4f)   // shorter when crouching
    val kneeR = 2.5f
    val torsoW = s * 0.5f
    val torsoH = s * 0.32f
    val headW = s * 0.42f
    val headH = s * 0.22f
    val armW = s * 0.10f
    val armH = s * 0.26f
    val antennaH = s * 0.12f

    // ── Y positions (bottom to top) ──
    val feetY = canvasY - footH
    val legTopY = feetY - legH
    val kneeY = feetY - legH * 0.5f

    // ── 2. Metal feet ──
    val leftFootX = charX - s * 0.22f + legSwing * 0.3f
    val rightFootX = charX + s * 0.04f - legSwing * 0.3f
    drawRoundRect(
        brush = ChromeGradient,
        topLeft = Offset(leftFootX - footW * 0.1f, feetY),
        size = Size(footW, footH),
        cornerRadius = CornerRadius(2f)
    )
    drawRoundRect(
        brush = ChromeGradient,
        topLeft = Offset(rightFootX - footW * 0.1f, feetY),
        size = Size(footW, footH),
        cornerRadius = CornerRadius(2f)
    )

    // ── 3. Piston legs ──
    drawRoundRect(
        brush = ChromeGradient,
        topLeft = Offset(leftFootX, legTopY),
        size = Size(legW, legH),
        cornerRadius = CornerRadius(2f)
    )
    // Shine highlight on left leg
    drawRect(
        color = Color.White.copy(alpha = 0.15f),
        topLeft = Offset(leftFootX + legW * 0.3f, legTopY + 2f),
        size = Size(legW * 0.2f, legH - 4f)
    )
    drawRoundRect(
        brush = ChromeGradient,
        topLeft = Offset(rightFootX, legTopY),
        size = Size(legW, legH),
        cornerRadius = CornerRadius(2f)
    )
    // Shine highlight on right leg
    drawRect(
        color = Color.White.copy(alpha = 0.15f),
        topLeft = Offset(rightFootX + legW * 0.3f, legTopY + 2f),
        size = Size(legW * 0.2f, legH - 4f)
    )

    // ── 4. Knee joints ──
    drawCircle(
        color = DgbBlue,
        radius = kneeR,
        center = Offset(leftFootX + legW * 0.5f, kneeY)
    )
    drawCircle(
        color = DgbBlue,
        radius = kneeR,
        center = Offset(rightFootX + legW * 0.5f, kneeY)
    )

    // ── 5. Chrome torso (with crouch scale transform) ──
    val torsoBaseY = legTopY - torsoH
    val torsoScaleX = 1f + crouch * 0.1f
    val torsoScaleY = 1f - crouch * 0.2f
    val torsoCenterX = charX
    val torsoCenterY = torsoBaseY + torsoH * 0.5f

    withTransform({
        scale(torsoScaleX, torsoScaleY, Offset(torsoCenterX, torsoCenterY))
    }) {
        drawRoundRect(
            brush = ChromeGradient,
            topLeft = Offset(charX - torsoW * 0.5f, torsoBaseY),
            size = Size(torsoW, torsoH),
            cornerRadius = CornerRadius(4f)
        )

        // ── 6. DGB logo on chest ──
        val logoR = torsoH * 0.28f
        val logoCX = charX
        val logoCY = torsoBaseY + torsoH * 0.45f
        drawCircle(color = DgbBlue, radius = logoR, center = Offset(logoCX, logoCY))
        // White "D" — vertical bar + arc
        drawRect(
            color = Color.White,
            topLeft = Offset(logoCX - logoR * 0.35f, logoCY - logoR * 0.5f),
            size = Size(1.5f, logoR)
        )
        drawArc(
            color = Color.White,
            startAngle = -90f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(logoCX - logoR * 0.35f, logoCY - logoR * 0.5f),
            size = Size(logoR * 0.8f, logoR),
            style = Stroke(width = 1.5f)
        )

        // ── 7. Energy seam ──
        drawLine(
            color = DgbBlue.copy(alpha = 0.4f),
            start = Offset(charX - torsoW * 0.45f, torsoCenterY),
            end = Offset(charX + torsoW * 0.45f, torsoCenterY),
            strokeWidth = 1f
        )
    }

    // ── 8. Arms ──
    val shoulderY = torsoBaseY + torsoH * 0.15f
    // Left arm
    val leftArmX = charX - torsoW * 0.5f - armW
    drawRoundRect(
        brush = ChromeGradient,
        topLeft = Offset(leftArmX, shoulderY + armSwing),
        size = Size(armW, armH),
        cornerRadius = CornerRadius(2f)
    )
    drawCircle(
        color = DgbBlue,
        radius = 2.5f,
        center = Offset(leftArmX + armW * 0.5f, shoulderY)
    )
    // Right arm
    val rightArmX = charX + torsoW * 0.5f
    drawRoundRect(
        brush = ChromeGradient,
        topLeft = Offset(rightArmX, shoulderY - armSwing),
        size = Size(armW, armH),
        cornerRadius = CornerRadius(2f)
    )
    drawCircle(
        color = DgbBlue,
        radius = 2.5f,
        center = Offset(rightArmX + armW * 0.5f, shoulderY)
    )

    // ── 9. Chrome head ──
    val headTopY = torsoBaseY - headH - 2f
    drawRoundRect(
        brush = ChromeGradient,
        topLeft = Offset(charX - headW * 0.5f, headTopY),
        size = Size(headW, headH),
        cornerRadius = CornerRadius(4f)
    )

    // ── 10. LED visor ──
    val visorY = headTopY + headH * 0.25f
    val visorH = headH * 0.28f
    val visorColor = when {
        isStumbling && ((state.stumbleTimer * 10f).toInt() % 2 == 0) -> Color(0xFFFF4444)
        isStumbling -> Color(0xFFFF4444)
        sprintMult > 1.05f -> Color(0xFF00DDFF)
        else -> DgbBlue
    }
    drawRoundRect(
        color = visorColor,
        topLeft = Offset(charX - headW * 0.4f, visorY),
        size = Size(headW * 0.8f, visorH),
        cornerRadius = CornerRadius(2f)
    )
    // Visor shine
    drawRect(
        color = Color.White.copy(alpha = 0.3f),
        topLeft = Offset(charX - headW * 0.3f, visorY + 1f),
        size = Size(headW * 0.3f, visorH * 0.4f)
    )

    // ── 11. Antenna ──
    val antennaBaseY = headTopY
    val antennaTipY = antennaBaseY - antennaH
    drawLine(
        color = Color(0xFFBBCCDD),
        start = Offset(charX, antennaBaseY),
        end = Offset(charX, antennaTipY),
        strokeWidth = 1.5f
    )
    drawCircle(
        color = DgbBlue,
        radius = 2f,
        center = Offset(charX, antennaTipY)
    )
}

// ─────────────────────────────────────────────────────────────────────────────

/** Draw DigiByte coins — blue with "D" shield. */
fun DrawScope.drawCoins(state: GameState) {
    val gndY = groundYCanvas()
    state.coins.filter { !it.collected }.forEach { coin ->
        val screenX = coin.x - state.scrollOffset
        if (screenX < -GamePhysics.COIN_SIZE || screenX > size.width + GamePhysics.COIN_SIZE) return@forEach

        val screenY = gndY - coin.y - GamePhysics.COIN_SIZE / 2f
        val r = GamePhysics.COIN_SIZE / 2f

        // Glow behind coin
        drawCircle(
            color = DgbLight.copy(alpha = 0.3f),
            radius = r + 4f,
            center = Offset(screenX, screenY)
        )
        // Outer blue circle
        drawCircle(color = DgbBlue, radius = r, center = Offset(screenX, screenY))
        // Inner ring
        drawCircle(
            color = DgbLight,
            radius = r - 3f,
            center = Offset(screenX, screenY),
            style = Stroke(width = 2f)
        )
        // Highlight arc (top-left shine)
        drawArc(
            color = CoinShine.copy(alpha = 0.5f),
            startAngle = 200f,
            sweepAngle = 80f,
            useCenter = false,
            topLeft = Offset(screenX - r, screenY - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = 2f)
        )
        // "D" letter
        drawRect(
            color = Color.White,
            topLeft = Offset(screenX - r * 0.35f, screenY - r * 0.5f),
            size = Size(2.5f, r)
        )
        drawArc(
            color = Color.White,
            startAngle = -90f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(screenX - r * 0.35f, screenY - r * 0.5f),
            size = Size(r * 0.9f, r),
            style = Stroke(width = 2.5f)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/** Draw obstacles — warning blocks with hazard stripes. */
fun DrawScope.drawObstacles(state: GameState) {
    val gndY = groundYCanvas()
    state.obstacles.forEach { obs ->
        val screenX = obs.x - state.scrollOffset
        if (screenX + obs.width < 0 || screenX > size.width) return@forEach

        val top = gndY - obs.height

        // Danger glow beneath
        drawRect(
            color = ObsGlow.copy(alpha = 0.15f),
            topLeft = Offset(screenX - 4f, top - 4f),
            size = Size(obs.width + 8f, obs.height + 4f)
        )

        // Main block
        drawRect(
            color = ObsBody,
            topLeft = Offset(screenX, top),
            size = Size(obs.width, obs.height)
        )

        // Hazard stripes (diagonal yellow/black)
        val stripeW = 6f
        var sx = screenX
        while (sx < screenX + obs.width) {
            drawLine(
                color = ObsStripe.copy(alpha = 0.7f),
                start = Offset(sx, top),
                end = Offset(sx + stripeW, top + obs.height.coerceAtMost(stripeW * 2f)),
                strokeWidth = 3f
            )
            sx += stripeW * 2f
        }

        // Top warning bar
        drawRect(
            color = ObsStripe,
            topLeft = Offset(screenX, top),
            size = Size(obs.width, 3f)
        )

        // Side shadow
        drawRect(
            color = Color.Black.copy(alpha = 0.4f),
            topLeft = Offset(screenX + obs.width - 3f, top),
            size = Size(3f, obs.height)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/** Draw score and branding in the HUD. */
fun DrawScope.drawScore(textMeasurer: TextMeasurer, score: Int) {
    // "DigiByte" branding top-left
    val brandStyle = TextStyle(
        color = DgbLight.copy(alpha = 0.7f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold
    )
    drawText(
        textMeasurer = textMeasurer,
        text = "DigiByte",
        topLeft = Offset(10f, 8f),
        style = brandStyle
    )

    // Score top-right
    val text = "DGB: $score"
    val style = TextStyle(
        color = ScoreColor,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold
    )
    val measured = textMeasurer.measure(text, style)
    drawText(
        textMeasurer = textMeasurer,
        text = text,
        topLeft = Offset(size.width - measured.size.width - 12f, 10f),
        style = style
    )
}
