package io.digibyte.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Colour palette (matching DigiByte blue/purple mountain aesthetic) ────────

private val SkyTop    = Color(0xFF0D1B3E)   // deep navy
private val SkyBot    = Color(0xFF2D1B69)   // purple
private val MtnFar    = Color(0xFF1A2A5E)   // dark-blue mountains
private val MtnNear   = Color(0xFF0F1D4A)   // slightly darker silhouette
private val GroundTop = Color(0xFF1E3A1E)   // pixel grass
private val GroundMid = Color(0xFF8B6914)   // soil / dirt
private val CoinGold  = Color(0xFFFFD700)
private val CoinRim   = Color(0xFFB8860B)
private val CoinText  = Color(0xFF3D2B00)
private val CharHead  = Color(0xFFFFDBAC)   // skin tone
private val CharBody  = Color(0xFF0066CC)   // DigiByte blue shirt
private val CharLegs  = Color(0xFF1A1A2E)   // dark trousers
private val ObsColor  = Color(0xFFCC2222)   // red obstacle
private val ObsShadow = Color(0xFF881111)

// ─────────────────────────────────────────────────────────────────────────────

/** Draw the gradient sky. */
fun DrawScope.drawBackground(scrollOffset: Float) {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(SkyTop, SkyBot),
            startY = 0f,
            endY = size.height
        )
    )
    drawFarMountains(scrollOffset)
    drawNearMountains(scrollOffset)
}

/** Distant mountain range — slow parallax (20 % scroll speed). */
private fun DrawScope.drawFarMountains(scrollOffset: Float) {
    val horizonY = size.height * 0.55f
    val parallax = scrollOffset * 0.2f
    val peakData = listOf(
        0f     to 0.28f,
        0.18f  to 0.12f,
        0.35f  to 0.22f,
        0.52f  to 0.08f,
        0.68f  to 0.20f,
        0.84f  to 0.14f,
        1.0f   to 0.26f,
        1.18f  to 0.10f,
        1.35f  to 0.18f,
    )
    val path = Path()
    val w = size.width
    path.moveTo(-parallax % w - w, horizonY)
    peakData.forEach { (xRatio, heightRatio) ->
        val x = xRatio * w - (parallax % w)
        val y = horizonY - heightRatio * horizonY
        path.lineTo(x, y)
        // Small plateau at each peak for a pixel-art look
        path.lineTo(x + 18f, y)
    }
    // Wrap a second copy so no gap at right edge
    peakData.forEach { (xRatio, heightRatio) ->
        val x = xRatio * w + w - (parallax % w)
        val y = horizonY - heightRatio * horizonY
        path.lineTo(x, y)
        path.lineTo(x + 18f, y)
    }
    path.lineTo(w * 3f, horizonY)
    path.close()
    drawPath(path = path, color = MtnFar, style = Fill)
}

/** Near mountain range — medium parallax (40 % scroll speed). */
private fun DrawScope.drawNearMountains(scrollOffset: Float) {
    val horizonY = size.height * 0.60f
    val parallax = scrollOffset * 0.4f
    val peakData = listOf(
        0.0f  to 0.16f,
        0.14f to 0.06f,
        0.28f to 0.18f,
        0.44f to 0.04f,
        0.60f to 0.14f,
        0.76f to 0.09f,
        0.92f to 0.17f,
        1.08f to 0.05f,
        1.24f to 0.13f,
    )
    val path = Path()
    val w = size.width
    path.moveTo(-parallax % w - w, horizonY)
    peakData.forEach { (xRatio, heightRatio) ->
        val x = xRatio * w - (parallax % w)
        val y = horizonY - heightRatio * horizonY
        path.lineTo(x, y)
        path.lineTo(x + 10f, y)
    }
    peakData.forEach { (xRatio, heightRatio) ->
        val x = xRatio * w + w - (parallax % w)
        val y = horizonY - heightRatio * horizonY
        path.lineTo(x, y)
        path.lineTo(x + 10f, y)
    }
    path.lineTo(w * 3f, horizonY)
    path.close()
    drawPath(path = path, color = MtnNear, style = Fill)
}

// ─────────────────────────────────────────────────────────────────────────────

/** Pixel-art ground strip at the bottom of the canvas. */
fun DrawScope.drawGround(scrollOffset: Float) {
    val groundTop  = size.height * 0.75f
    val grassH     = 8f
    val dirtH      = size.height - groundTop - grassH

    // Grass row
    drawRect(
        color = GroundTop,
        topLeft = Offset(0f, groundTop),
        size    = Size(size.width, grassH)
    )
    // Dirt fill
    drawRect(
        color = GroundMid,
        topLeft = Offset(0f, groundTop + grassH),
        size    = Size(size.width, dirtH)
    )

    // Pixel tile lines (evenly spaced vertical notches on the grass edge)
    val tileW = 32f
    val tileOffset = scrollOffset % tileW
    var x = -tileOffset
    while (x < size.width) {
        drawRect(
            color   = GroundTop.copy(alpha = 0.4f),
            topLeft = Offset(x, groundTop),
            size    = Size(2f, grassH)
        )
        x += tileW
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Ground Y in canvas coordinates.
 * GamePhysics uses Y-up (characterY=0 → on ground).
 * We translate that to canvas-space here.
 */
private fun DrawScope.groundYCanvas(): Float = size.height * 0.75f

/** Convert a physics Y (up-positive, 0 = ground) to canvas Y (down-positive). */
private fun DrawScope.physToCanvas(physY: Float): Float =
    groundYCanvas() - physY

// ─────────────────────────────────────────────────────────────────────────────

/** Pixel-art running character. */
fun DrawScope.drawCharacter(state: GameState) {
    val charX   = 60f
    val canvasY = physToCanvas(state.characterY)
    val s       = GamePhysics.CHARACTER_SIZE
    val t       = state.scrollOffset           // used to animate legs

    // Shadow on the ground
    drawOval(
        color   = Color.Black.copy(alpha = 0.25f),
        topLeft = Offset(charX - s * 0.4f, groundYCanvas() - 3f),
        size    = Size(s * 0.8f, 6f)
    )

    // Head (rounded rect)
    val headSize = s * 0.38f
    drawRoundRect(
        color     = CharHead,
        topLeft   = Offset(charX - headSize / 2f, canvasY - s - headSize),
        size      = Size(headSize, headSize),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f)
    )
    // Eye
    drawCircle(
        color  = Color(0xFF333333),
        radius = 2f,
        center = Offset(charX + headSize * 0.2f, canvasY - s - headSize * 0.55f)
    )

    // Body
    drawRoundRect(
        color     = CharBody,
        topLeft   = Offset(charX - s * 0.25f, canvasY - s),
        size      = Size(s * 0.5f, s * 0.5f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f)
    )

    // Legs — simple alternating rectangles to simulate a running animation
    val legPhase = if (state.isJumping) 0f else (t * 0.02f) % (2f * Math.PI.toFloat())
    val legSwing = kotlin.math.sin(legPhase) * 6f

    // Left leg
    drawRect(
        color   = CharLegs,
        topLeft = Offset(charX - s * 0.22f, canvasY - s * 0.5f),
        size    = Size(s * 0.18f, s * 0.5f + legSwing)
    )
    // Right leg
    drawRect(
        color   = CharLegs,
        topLeft = Offset(charX + s * 0.04f, canvasY - s * 0.5f),
        size    = Size(s * 0.18f, s * 0.5f - legSwing)
    )

    // Arms
    val armSwing = kotlin.math.cos(legPhase) * 8f
    drawLine(
        color       = CharBody,
        start       = Offset(charX - s * 0.25f, canvasY - s * 0.85f),
        end         = Offset(charX - s * 0.5f, canvasY - s * 0.5f + armSwing),
        strokeWidth = 5f
    )
    drawLine(
        color       = CharBody,
        start       = Offset(charX + s * 0.25f, canvasY - s * 0.85f),
        end         = Offset(charX + s * 0.5f, canvasY - s * 0.5f - armSwing),
        strokeWidth = 5f
    )
}

// ─────────────────────────────────────────────────────────────────────────────

/** Draw DGB coins floating in the world. */
fun DrawScope.drawCoins(state: GameState) {
    val gndY = groundYCanvas()
    state.coins.filter { !it.collected }.forEach { coin ->
        val screenX = coin.x - state.scrollOffset
        if (screenX < -GamePhysics.COIN_SIZE || screenX > size.width + GamePhysics.COIN_SIZE) return@forEach

        // Coins float: their physics Y is relative to ground (0 = ground-level centre)
        val screenY = gndY - coin.y - GamePhysics.COIN_SIZE / 2f

        val r = GamePhysics.COIN_SIZE / 2f

        // Outer gold circle
        drawCircle(color = CoinGold, radius = r, center = Offset(screenX, screenY))
        // Inner ring
        drawCircle(
            color       = CoinRim,
            radius      = r - 3f,
            center      = Offset(screenX, screenY),
            style       = Stroke(width = 2f)
        )
        // "D" letter in centre — drawn as two arcs/lines using rectangles for pixel art
        drawRect(
            color   = CoinText,
            topLeft = Offset(screenX - r * 0.35f, screenY - r * 0.5f),
            size    = Size(3f, r)
        )
        drawArc(
            color      = CoinText,
            startAngle = -90f,
            sweepAngle = 180f,
            useCenter  = false,
            topLeft    = Offset(screenX - r * 0.35f, screenY - r * 0.5f),
            size       = Size(r * 0.9f, r),
            style      = Stroke(width = 3f)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/** Draw red obstacle blocks. */
fun DrawScope.drawObstacles(state: GameState) {
    val gndY = groundYCanvas()
    state.obstacles.forEach { obs ->
        val screenX = obs.x - state.scrollOffset
        if (screenX + obs.width < 0 || screenX > size.width) return@forEach

        val top = gndY - obs.height
        // Main block
        drawRect(
            color   = ObsColor,
            topLeft = Offset(screenX, top),
            size    = Size(obs.width, obs.height)
        )
        // Top highlight strip
        drawRect(
            color   = ObsColor.copy(red = 1f, alpha = 0.7f),
            topLeft = Offset(screenX, top),
            size    = Size(obs.width, 4f)
        )
        // Side shadow
        drawRect(
            color   = ObsShadow,
            topLeft = Offset(screenX + obs.width - 4f, top),
            size    = Size(4f, obs.height)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/** Draw score text in the top-right corner. */
fun DrawScope.drawScore(textMeasurer: TextMeasurer, score: Int) {
    val text = "DGB: $score"
    val style = TextStyle(
        color      = CoinGold,
        fontSize   = 14.sp,
        fontWeight = FontWeight.Bold
    )
    val measured = textMeasurer.measure(text, style)
    drawText(
        textMeasurer = textMeasurer,
        text         = text,
        topLeft      = Offset(size.width - measured.size.width - 12f, 12f),
        style        = style
    )
}
