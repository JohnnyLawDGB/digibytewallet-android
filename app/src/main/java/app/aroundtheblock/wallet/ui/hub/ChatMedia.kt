// Compose functions are named as the nouns they draw (`PreviewCard`), as across the app's UI.
@file:Suppress("FunctionNaming")

package app.aroundtheblock.wallet.ui.hub

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.core.hub.ChatAttachment
import app.aroundtheblock.wallet.core.hub.ChatMessage
import app.aroundtheblock.wallet.core.hub.HubMedia
import app.aroundtheblock.wallet.core.hub.LinkPreview
import app.aroundtheblock.wallet.ui.theme.DigiByteAccent
import app.aroundtheblock.wallet.ui.theme.DigiByteBlue
import app.aroundtheblock.wallet.ui.util.openExternalUrl
import coil.compose.AsyncImage

/**
 * Opens [url] in the browser when it is an http(s) link, through [openExternalUrl] (the app's one
 * way out to another app, which checks the URL again); anything else is not opened.
 */
private fun openWebLink(context: Context, url: String) {
    if (ChatText.isWebLink(url)) openExternalUrl(context, url)
}

/**
 * A message's text as plain text, its `http(s)://` links tappable (opened in the browser). The
 * text is never read as HTML or markup ([ChatText]).
 */
@Composable
internal fun ChatMessageText(content: String) {
    val context = LocalContext.current
    val linkStyle = TextLinkStyles(SpanStyle(color = DigiByteAccent, textDecoration = TextDecoration.Underline))
    val text = remember(content) {
        buildAnnotatedString {
            for (piece in ChatText.pieces(content)) {
                when (piece) {
                    is ChatText.Piece.Plain -> append(piece.text)
                    is ChatText.Piece.Link -> withLink(
                        LinkAnnotation.Clickable(piece.text, linkStyle) { openWebLink(context, piece.text) }
                    ) { append(piece.text) }
                }
            }
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** A message's text (if any), its image (if any) and its link preview (if any), in that order. */
@Composable
internal fun BubbleContent(message: ChatMessage, onOpenImage: (ChatAttachment) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        if (message.content.isNotEmpty()) ChatMessageText(message.content)
        message.attachment?.let { attachment -> ChatImage(attachment = attachment, onOpen = { onOpenImage(attachment) }) }
        message.preview?.let { PreviewCard(it) }
    }
}

/** The long-press menu on someone else's message: Report. */
@Composable
internal fun ReportMenu(expanded: Boolean, onDismiss: () -> Unit, onReport: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.hub_report)) },
            onClick = {
                onDismiss()
                onReport()
            },
        )
    }
}

/** Above the composer: the server's own words for a refused post, and a fixed notice. Tap either to clear it. */
@Composable
internal fun ChatBanners(sendError: String?, notice: String?, onClearError: () -> Unit, onClearNotice: () -> Unit) {
    sendError?.let {
        Banner(it, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, onClearError)
    }
    notice?.let {
        Banner(it, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, onClearNotice)
    }
}

@Composable
private fun Banner(text: String, background: Color, foreground: Color, onClick: () -> Unit) {
    Surface(color = background, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/**
 * An image posted in the chat, at its own shape within the bubble; tap to see it full size.
 *
 * The image loader is handed only what [HubMedia.imageUrl] answers: a Hub media file on the
 * DigiScope API host. Nothing else in a message (a link, a preview's image URL) is ever fetched,
 * so a post cannot make the wallets that show it contact a host of the poster's choosing
 * (HubImageSourcesGateTest).
 */
@Composable
internal fun ChatImage(attachment: ChatAttachment, onOpen: () -> Unit) {
    val mediaUrl = HubMedia.imageUrl(attachment) ?: return
    val ratio = if (attachment.width > 0 && attachment.height > 0) {
        (attachment.width.toFloat() / attachment.height).coerceIn(0.3f, 3f)
    } else {
        1f
    }
    AsyncImage(
        model = mediaUrl,
        contentDescription = stringResource(R.string.hub_chat_image),
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .padding(top = 4.dp)
            .widthIn(max = 240.dp)
            .heightIn(max = 320.dp)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onOpen),
    )
}

/**
 * The server's preview of a link, as text only: the link's host (from its URL), the page's own
 * site name, title and description. Its `image_url` is never loaded: it names a host the poster
 * chose, and fetching it would show that host the address of every wallet that scrolled past.
 * Tapping the card opens the link when it is an http(s) link.
 */
@Composable
internal fun PreviewCard(preview: LinkPreview) {
    val context = LocalContext.current
    val host = ChatText.previewHost(preview.url)
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .padding(top = 6.dp)
            .widthIn(max = 260.dp)
            .then(if (host != null) Modifier.clickable { openWebLink(context, preview.url) } else Modifier),
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            host?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            preview.siteName?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            preview.title?.let {
                Text(
                    it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            preview.description?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The composer's button that opens the system photo picker. */
@Composable
internal fun AttachImageButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Icon(
            imageVector = Icons.Default.Image,
            contentDescription = stringResource(R.string.hub_attach_image),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The composer's send button. */
@Composable
internal fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (enabled) DigiByteBlue else MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = "Send",
            tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The image picked for the next post, above the composer, with a way to take it off. */
@Composable
internal fun PendingImageRow(pendingImage: Uri, uploading: Boolean, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AsyncImage(
            model = pendingImage,
            contentDescription = stringResource(R.string.hub_image_attached),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        if (uploading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.hub_uploading),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.hub_remove_image),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** In place of the composer for an account the server does not let post: what to do next. */
@Composable
internal fun PostGateNote() {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
        Text(
            text = stringResource(R.string.hub_post_gate_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

/** A chat image full size, fitted to the screen; tap anywhere, the close button or back to leave. */
@Composable
internal fun HubImageViewer(attachment: ChatAttachment, onDismiss: () -> Unit) {
    val mediaUrl = HubMedia.imageUrl(attachment) ?: return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = mediaUrl,
                contentDescription = stringResource(R.string.hub_chat_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.hub_image_close), tint = Color.White)
            }
        }
    }
}
