package io.digibyte.ui.asset

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

/**
 * Full-screen view of a DigiAsset's artwork.
 *
 * The detail header shows the artwork as a 72dp circle with [ContentScale.Crop], which is a
 * thumbnail — it crops to a square and then to a circle, so anything that is not already
 * square loses its edges. This exists so the user can actually see the whole piece.
 *
 * **The image is never altered.** [ContentScale.Fit] scales it to the largest size that fits
 * the screen while preserving its aspect ratio: no cropping, no stretching, no circle mask.
 * A tall image letterboxes left and right, a wide one top and bottom, and the black backdrop
 * is what fills the remainder rather than any part of the image being sacrificed to it.
 *
 * Dismissed by tapping anywhere, by the close button, or by the system back gesture —
 * viewing artwork should never be something the user has to work out how to leave.
 *
 * **Scope:** static images only (PNG/JPEG/WebP, and the first frame of a GIF). The project
 * depends on `coil-compose` alone — no `coil-gif`, no `coil-video`, no media3 — so animation
 * and video would each need a new dependency rather than a change here.
 */
@Composable
fun AssetMediaViewer(
    imageUrl: String?,
    onDismiss: () -> Unit,
) {
    val model = remember(imageUrl) { AssetImageResolver.resolve(imageUrl) } ?: return

    Dialog(
        onDismissRequest = onDismiss,
        // Default dialog width is inset and capped; the whole point here is to use the
        // entire screen, so the platform sizing is turned off.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // No ripple and no minimum touch target: the whole backdrop is a dismiss
                // affordance, and a ripple across the full screen would read as a glitch.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = model,
                contentDescription = "Asset artwork, full screen",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                )
            }
        }
    }
}
