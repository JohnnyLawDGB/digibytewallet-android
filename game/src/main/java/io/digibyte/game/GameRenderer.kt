package io.digibyte.game

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
private val StumbleRed  = Color(0xFFFF4444)   // flash red on hit

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
    // DGB moon — always visible in the sky
    drawDgbMoon(scrollOffset)
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

/**
 * Build the official DigiByte "D" mark as a Compose Path.
 * Traced from DigiByte-Core/digibyte-logos SVG (viewBox 0 0 1280 1280).
 * The path is normalized to fit a 1x1 box, then scaled at draw time.
 */
private fun buildDgbMarkPath(): androidx.compose.ui.graphics.Path = androidx.compose.ui.graphics.Path().apply {
    // SVG path normalized: original coords / 1280, centered around (0.5, 0.5)
    // Original SVG "d" attribute from the st2 (white) path, simplified key points.
    // The mark spans roughly x=380..860, y=380..900 in the 1280 viewport.
    val s = 1f / 1280f  // normalize to 0..1

    moveTo(769.9f * s, 428f * s)
    lineTo(784.9f * s, 388.9f * s)
    cubicTo(786.4f * s, 384.9f * s, 783.5f * s, 380.7f * s, 779.3f * s, 380.7f * s)
    lineTo(723.6f * s, 380.7f * s)
    lineTo(706f * s, 426.5f * s)
    lineTo(681.3f * s, 426.5f * s)
    lineTo(695.7f * s, 388.9f * s)
    cubicTo(697.2f * s, 384.9f * s, 694.3f * s, 380.7f * s, 690.1f * s, 380.7f * s)
    lineTo(634.4f * s, 380.7f * s)
    lineTo(616.8f * s, 426.5f * s)
    lineTo(442.6f * s, 426.5f * s)
    cubicTo(434.7f * s, 426.5f * s, 427.3f * s, 430.8f * s, 423.4f * s, 437.7f * s)
    lineTo(380f * s, 514.3f * s)
    lineTo(440.7f * s, 514.3f * s)
    lineTo(705.5f * s, 514.3f * s)
    cubicTo(716.3f * s, 514.3f * s, 727f * s, 516.3f * s, 737f * s, 520.4f * s)
    cubicTo(756.2f * s, 528.3f * s, 778.9f * s, 546.2f * s, 773.2f * s, 586.5f * s)
    cubicTo(763.7f * s, 654f * s, 694.9f * s, 773.6f * s, 545.6f * s, 775.5f * s)
    lineTo(622.8f * s, 574.5f * s)
    cubicTo(626f * s, 566.2f * s, 619.9f * s, 557.4f * s, 611f * s, 557.4f * s)
    lineTo(507.5f * s, 557.4f * s)
    lineTo(382.5f * s, 864.6f * s)
    cubicTo(382.5f * s, 864.6f * s, 407.7f * s, 867.7f * s, 447.2f * s, 867.7f * s)
    lineTo(434.8f * s, 900f * s)
    lineTo(491.7f * s, 900f * s)
    cubicTo(496.2f * s, 900f * s, 500.3f * s, 897.2f * s, 502f * s, 893f * s)
    lineTo(512.7f * s, 865.2f * s)
    cubicTo(521.1f * s, 864.5f * s, 529.6f * s, 863.6f * s, 538.4f * s, 862.6f * s)
    lineTo(524f * s, 900f * s)
    lineTo(580.9f * s, 900f * s)
    cubicTo(585.4f * s, 900f * s, 589.5f * s, 897.2f * s, 591.2f * s, 893f * s)
    lineTo(607.4f * s, 850.7f * s)
    cubicTo(700.9f * s, 829.4f * s, 801.4f * s, 783f * s, 861.1f * s, 685.1f * s)
    cubicTo(981.6f * s, 487.7f * s, 856.4f * s, 436.7f * s, 769.9f * s, 428f * s)
    close()
}

/** Cached path instance — built once. */
private var dgbMarkPath: androidx.compose.ui.graphics.Path? = null
private fun getDgbMarkPath(): androidx.compose.ui.graphics.Path {
    if (dgbMarkPath == null) dgbMarkPath = buildDgbMarkPath()
    return dgbMarkPath!!
}

/** DGB logo as a glowing moon — slow parallax drift in the sky. */
private fun DrawScope.drawDgbMoon(scrollOffset: Float) {
    val moonRadius = size.height * 0.12f
    val baseX = size.width * 0.78f
    val drift = (scrollOffset * 0.03f) % size.width
    val moonX = ((baseX - drift) % size.width + size.width) % size.width
    val moonY = size.height * 0.15f

    // Outer glow
    drawCircle(color = DgbBlue.copy(alpha = 0.12f), radius = moonRadius * 2f, center = Offset(moonX, moonY))
    drawCircle(color = DgbBlue.copy(alpha = 0.2f), radius = moonRadius * 1.4f, center = Offset(moonX, moonY))

    // Outer ring — official DGB blue
    drawCircle(color = DgbBlue, radius = moonRadius, center = Offset(moonX, moonY))

    // Inner circle — official DGB dark
    drawCircle(color = DgbDark, radius = moonRadius * 0.81f, center = Offset(moonX, moonY))

    // Official DGB mark — scaled and centered on the moon
    // The SVG mark spans roughly (0.297..0.767, 0.297..0.703) in normalized coords
    val markCenterX = 0.53f  // approximate center of the SVG mark
    val markCenterY = 0.52f
    val markScale = moonRadius * 2.2f  // scale factor to fill the inner circle

    drawContext.canvas.save()
    drawContext.canvas.translate(
        moonX - markCenterX * markScale,
        moonY - markCenterY * markScale
    )
    drawContext.canvas.scale(markScale, markScale)

    val path = getDgbMarkPath()
    drawPath(path = path, color = Color.White)

    drawContext.canvas.restore()

    // Subtle highlight (moonlight sheen)
    drawArc(
        color = Color.White.copy(alpha = 0.12f),
        startAngle = 200f, sweepAngle = 100f, useCenter = false,
        topLeft = Offset(moonX - moonRadius, moonY - moonRadius),
        size = Size(moonRadius * 2f, moonRadius * 2f),
        style = Stroke(width = 2f)
    )
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
        isStumbling && ((state.stumbleTimer * 10f).toInt() % 2 == 0) -> StumbleRed
        isStumbling -> StumbleRed
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

/** Draw 3D Y-axis spinning DigiByte coins. */
fun DrawScope.drawCoins(state: GameState) {
    val gndY = groundYCanvas()
    val coinSize = GamePhysics.COIN_SIZE
    val r = coinSize / 2f

    state.coins.filter { !it.collected }.forEach { coin ->
        val screenX = coin.x - state.scrollOffset
        if (screenX < -coinSize || screenX > size.width + coinSize) return@forEach

        val screenY = gndY - coin.y - r
        val center = Offset(screenX, screenY)
        val cosA = kotlin.math.cos(coin.rotationAngle)
        val absCos = kotlin.math.abs(cosA)

        val renderWidth = coinSize * absCos.coerceAtLeast(0.15f)

        // Glow behind coin
        drawCircle(
            color = DgbBlue.copy(alpha = 0.25f),
            radius = r + 4f,
            center = center
        )

        if (absCos < 0.15f) {
            // ── Edge view — thin rect ──
            drawRect(
                color = DgbDark,
                topLeft = Offset(screenX - 2f, screenY - r),
                size = Size(4f, coinSize)
            )
        } else if (cosA > 0f) {
            // ── Front face ──
            drawOval(
                color = DgbBlue,
                topLeft = Offset(screenX - renderWidth / 2f, screenY - r),
                size = Size(renderWidth, coinSize)
            )
            // Ring stroke
            drawOval(
                color = DgbLight,
                topLeft = Offset(screenX - renderWidth / 2f + 3f, screenY - r + 3f),
                size = Size(renderWidth - 6f, coinSize - 6f),
                style = Stroke(width = 2f)
            )
            // "D" letter — vertical bar + arc, scaled by absCos
            val dWidth = r * 0.9f * absCos
            val dLeft = screenX - dWidth * 0.35f
            drawRect(
                color = Color.White,
                topLeft = Offset(dLeft, screenY - r * 0.5f),
                size = Size(2.5f * absCos, r)
            )
            drawArc(
                color = Color.White,
                startAngle = -90f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(dLeft, screenY - r * 0.5f),
                size = Size(dWidth, r),
                style = Stroke(width = 2f)
            )
        } else {
            // ── Back face ──
            drawOval(
                color = DgbDark,
                topLeft = Offset(screenX - renderWidth / 2f, screenY - r),
                size = Size(renderWidth, coinSize)
            )
            // Faint ring
            drawOval(
                color = DgbBlue.copy(alpha = 0.3f),
                topLeft = Offset(screenX - renderWidth / 2f + 3f, screenY - r + 3f),
                size = Size(renderWidth - 6f, coinSize - 6f),
                style = Stroke(width = 1.5f)
            )
        }

        // Highlight glint — top-left
        drawOval(
            color = Color.White.copy(alpha = 0.3f * absCos),
            topLeft = Offset(screenX - renderWidth * 0.3f, screenY - r * 0.7f),
            size = Size(renderWidth * 0.3f, coinSize * 0.2f)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/** Draw BTC coin stack obstacles. */
fun DrawScope.drawBTCStacks(state: GameState) {
    val groundY = groundYCanvas()
    val coinR = GamePhysics.BTC_COIN_DIAMETER / 2f   // 18f
    val overlap = GamePhysics.BTC_STACK_OVERLAP        // 16f
    val coinStep = GamePhysics.BTC_COIN_DIAMETER - overlap

    state.obstacles.forEach { obs ->
        val baseScreenX = obs.x - state.scrollOffset
        if (baseScreenX + obs.width < 0 || baseScreenX > size.width) return@forEach

        for (i in 0 until obs.stackCount) {
            val coinCenterY = groundY - coinR - i * coinStep
            val coinCenterX = baseScreenX + coinR + if (i % 2 == 1) 2f else -1f

            // Orange glow behind each coin
            drawCircle(
                color = BtcOrange.copy(alpha = 0.15f),
                radius = 22f,
                center = Offset(coinCenterX, coinCenterY)
            )

            // Coin body — top coin brighter
            val bodyColor = if (i == obs.stackCount - 1) BtcOrange else BtcDark
            drawCircle(
                color = bodyColor,
                radius = coinR,
                center = Offset(coinCenterX, coinCenterY)
            )

            // Edge ring
            val ringColor = if (i == obs.stackCount - 1) BtcLight else BtcOrange
            drawCircle(
                color = ringColor,
                radius = coinR - 2f,
                center = Offset(coinCenterX, coinCenterY),
                style = Stroke(width = 2f)
            )

            // Shadow between coins (below each coin except bottom)
            if (i > 0) {
                drawOval(
                    color = Color.Black.copy(alpha = 0.25f),
                    topLeft = Offset(coinCenterX - coinR * 0.7f, coinCenterY + coinR - 2f),
                    size = Size(coinR * 1.4f, 4f)
                )
            }

            // "B" symbol on top coin (shape-based approximation)
            if (i == obs.stackCount - 1) {
                // Vertical bar
                drawRect(
                    color = Color.White,
                    topLeft = Offset(coinCenterX - 3f, coinCenterY - coinR * 0.5f),
                    size = Size(2f, coinR)
                )
                // Top bump of B
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(coinCenterX - 3f, coinCenterY - coinR * 0.5f),
                    size = Size(coinR * 0.6f, coinR * 0.5f),
                    style = Stroke(width = 1.5f)
                )
                // Bottom bump of B
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(coinCenterX - 3f, coinCenterY),
                    size = Size(coinR * 0.7f, coinR * 0.5f),
                    style = Stroke(width = 1.5f)
                )
                // Serifs (small vertical bars top and bottom)
                drawRect(
                    color = Color.White,
                    topLeft = Offset(coinCenterX - 4f, coinCenterY - coinR * 0.55f),
                    size = Size(6f, 1.5f)
                )
                drawRect(
                    color = Color.White,
                    topLeft = Offset(coinCenterX - 4f, coinCenterY + coinR * 0.45f),
                    size = Size(6f, 1.5f)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/** Draw 3 heart icons for remaining lives. */
fun DrawScope.drawHearts(state: GameState) {
    // Position: top-center, clear of branding (left) and score (right)
    val heartW = 20f
    val heartH = 18f
    val spacing = 28f
    val totalWidth = 3 * spacing
    val startX = (size.width - totalWidth) / 2f  // centered
    val y = 6f

    for (i in 0 until 3) {
        val cx = startX + i * spacing
        val filled = i < state.lives
        val color = if (filled) Color(0xFFFF4466) else Color(0xFF442233)  // red hearts, dark when lost

        val path = androidx.compose.ui.graphics.Path().apply {
            // Bottom point of heart
            moveTo(cx + heartW / 2f, y + heartH)
            // Left curve
            cubicTo(
                cx - heartW * 0.1f, y + heartH * 0.55f,
                cx - heartW * 0.15f, y + heartH * 0.1f,
                cx + heartW * 0.25f, y + heartH * 0.1f
            )
            // Top-left bump
            cubicTo(
                cx + heartW * 0.42f, y - heartH * 0.1f,
                cx + heartW * 0.5f, y + heartH * 0.05f,
                cx + heartW / 2f, y + heartH * 0.3f
            )
            // Top-right bump
            cubicTo(
                cx + heartW * 0.5f, y + heartH * 0.05f,
                cx + heartW * 0.58f, y - heartH * 0.1f,
                cx + heartW * 0.75f, y + heartH * 0.1f
            )
            // Right curve
            cubicTo(
                cx + heartW * 1.15f, y + heartH * 0.1f,
                cx + heartW * 1.1f, y + heartH * 0.55f,
                cx + heartW / 2f, y + heartH
            )
            close()
        }

        if (filled) {
            drawPath(path = path, color = color)
        } else {
            drawPath(path = path, color = color, style = Stroke(width = 1.5f))
        }
    }
}

/** Draw game-over overlay with score breakdown. */
fun DrawScope.drawGameOver(textMeasurer: TextMeasurer, state: GameState) {
    if (!state.isGameOver) return

    // Dark backdrop
    drawRect(Color.Black.copy(alpha = 0.7f))

    // Guard against tiny canvas
    if (size.width < 100f || size.height < 50f) return

    // "GAME OVER" — centered at 35% height
    val gameOverStyle = TextStyle(
        color = DgbBlue,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold
    )
    val gameOverMeasured = textMeasurer.measure("GAME OVER", gameOverStyle)
    drawText(
        textMeasurer = textMeasurer,
        text = "GAME OVER",
        topLeft = Offset(
            (size.width - gameOverMeasured.size.width) / 2f,
            size.height * 0.35f
        ),
        style = gameOverStyle
    )

    // Score breakdown — below GAME OVER
    val detailStyle = TextStyle(
        color = Color(0xFF888888),
        fontSize = 12.sp
    )
    val lineSpacing = 20f
    var lineY = size.height * 0.35f + gameOverMeasured.size.height + 12f

    // Distance
    val distText = "Distance: ${(state.scrollOffset / GamePhysics.DISTANCE_DIVISOR).toInt()}"
    val distMeasured = textMeasurer.measure(distText, detailStyle)
    drawText(
        textMeasurer = textMeasurer,
        text = distText,
        topLeft = Offset((size.width - distMeasured.size.width) / 2f, lineY),
        style = detailStyle
    )
    lineY += lineSpacing

    // Coins
    val coinsText = "Coins: ${state.score} \u00D7 5 = ${state.score * GamePhysics.COIN_SCORE_MULT}"
    val coinsMeasured = textMeasurer.measure(coinsText, detailStyle)
    drawText(
        textMeasurer = textMeasurer,
        text = coinsText,
        topLeft = Offset((size.width - coinsMeasured.size.width) / 2f, lineY),
        style = detailStyle
    )
    lineY += lineSpacing + 4f

    // TOTAL
    val totalStyle = TextStyle(
        color = DgbLight,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
    )
    val totalText = "TOTAL: ${state.finalScore}"
    val totalMeasured = textMeasurer.measure(totalText, totalStyle)
    drawText(
        textMeasurer = textMeasurer,
        text = totalText,
        topLeft = Offset((size.width - totalMeasured.size.width) / 2f, lineY),
        style = totalStyle
    )
}

// ─────────────────────────────────────────────────────────────────────────────

/** Draw HUD — branding, score, and sprint charge bar. */
fun DrawScope.drawHud(textMeasurer: TextMeasurer, state: GameState) {
    // Guard: skip HUD if canvas is too small for text layout
    if (size.width < 100f || size.height < 50f) return

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
    val text = "DGB: ${state.score}"
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

    // Sprint charge bar (visible when holding or sprint active)
    if (state.sprintMultiplier > 1.02f || state.isHolding) {
        val barW = 60f
        val barH = 6f
        val barX = 10f
        val barY = 26f
        val fill = ((state.sprintMultiplier - 1f) / (GamePhysics.SPRINT_MAX_MULT - 1f)).coerceIn(0f, 1f)

        drawRect(color = Color.Black.copy(alpha = 0.4f), topLeft = Offset(barX, barY), size = Size(barW, barH))
        drawRect(color = Color(0xFF00AAFF), topLeft = Offset(barX, barY), size = Size(barW * fill, barH))

        val label = if (fill >= 0.99f) "MAX" else "SPRINT"
        drawText(
            textMeasurer = textMeasurer,
            text = label,
            topLeft = Offset(barX, barY + barH + 2f),
            style = TextStyle(color = Color(0xFF00AAFF), fontSize = 10.sp)
        )
    }
}
