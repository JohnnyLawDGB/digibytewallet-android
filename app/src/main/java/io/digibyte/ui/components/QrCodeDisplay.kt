package io.digibyte.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

/**
 * Renders [content] as a QR code using ZXing.
 * Includes a white quiet-zone padding so scanners can read it against dark backgrounds.
 */
@Composable
fun QrCodeDisplay(
    content: String,
    size: Dp = 250.dp,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(content) {
        runCatching {
            val writer = MultiFormatWriter()
            val matrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512)
            val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
            for (x in 0 until 512) {
                for (y in 0 until 512) {
                    bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bmp.asImageBitmap()
        }.getOrNull()
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "QR Code",
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(8.dp)
        )
    }
}
