package lk.safeheaven.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import lk.safeheaven.ui.theme.Montserrat
import lk.safeheaven.ui.theme.Sh

/**
 * Numeric keypad that auto-submits at [length] digits. No haptics, no sounds and no per-key
 * differences, so the Real and Duress PINs look and feel identical.
 *
 * @param dim low-contrast variant used on the fake-shutdown exit screen.
 */
@Composable
fun PinPad(
    length: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    dim: Boolean = false,
    resetKey: Any = Unit,
    onComplete: (String) -> Unit,
) {
    var entered by remember(resetKey) { mutableStateOf("") }
    val fg = if (dim) Color(0xFF3A3A3A) else Sh.Bone
    val keyBg = if (dim) Color(0xFF0C0C0C) else Sh.SurfaceHigh

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(length) { i ->
                Box(
                    Modifier.size(14.dp).clip(CircleShape)
                        .background(if (i < entered.length) fg else fg.copy(alpha = 0.22f)),
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { key ->
                    if (key.isEmpty()) {
                        Spacer(Modifier.size(76.dp))
                    } else {
                        Box(
                            Modifier.size(76.dp).clip(CircleShape).background(keyBg)
                                .clickable(enabled = enabled) {
                                    if (key == "⌫") {
                                        entered = entered.dropLast(1)
                                    } else if (entered.length < length) {
                                        entered += key
                                        if (entered.length == length) onComplete(entered)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(key, color = fg, style = MaterialTheme.typography.headlineMedium.copy(fontFamily = Montserrat))
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}
