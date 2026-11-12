package app.aroundtheblock.wallet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aroundtheblock.wallet.BuildConfig

/**
 * A red "DEBUG" pill over every screen of a debug build, so a debug wallet installed beside the
 * release app can never be mistaken for it. The two share a name, an icon and the same screens,
 * and on the test phone a debug wallet's balance was once read as the release wallet's.
 *
 * Release builds compile `BuildConfig.DEBUG` to false, so this draws nothing there; that is also
 * why the label is a literal rather than a translated string. The pill handles no input, so
 * touches pass through to the screen beneath it.
 */
@Composable
fun DebugBuildBanner(modifier: Modifier = Modifier) {
    if (!BuildConfig.DEBUG) return
    Text(
        text = "DEBUG · ${BuildConfig.APPLICATION_ID}",
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 2.dp)
            .background(Color(0xCCD32F2F), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}
