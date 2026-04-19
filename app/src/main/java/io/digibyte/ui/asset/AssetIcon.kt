package io.digibyte.ui.asset

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Circular asset icon with a letter fallback rendered beneath an optional
 * image. Layering the letter under AsyncImage means the user sees the letter
 * throughout the load — no "flash to letter then image" transition. On load
 * failure (including IPFS CID verification failure), `error = null` leaves
 * the image slot transparent so the letter remains visible.
 */
@Composable
fun AssetIcon(
    imageUrl: String?,
    firstLetter: Char,
    iconColor: Color,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val model = remember(imageUrl) { AssetImageResolver.resolve(imageUrl) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(iconColor.copy(alpha = 0.18f))
            .border(1.5.dp, iconColor.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // Letter is always rendered; a successfully-loaded image covers it.
        Text(
            text = firstLetter.toString(),
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = if (size >= 64.dp) 28.sp else 22.sp
            ),
            color = iconColor
        )
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                error = null,
            )
        }
    }
}
