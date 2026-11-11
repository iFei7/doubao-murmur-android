package com.doubao.murmur.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.doubao.murmur.tv.ui.theme.LocalDimens
import com.doubao.murmur.tv.ui.theme.MurmurColors

/**
 * Bottom strip of key hints ("长按 OK 说话 · BACK 收起 · SEARCH 切换录音").
 *
 * The component owns typography and spacing only; the caller supplies already-localised strings
 * because the wording depends on context (idle vs listening vs error). It is deliberately not
 * focusable and not clickable: a hint bar that can take focus competes with the real controls for
 * the viewer's D-pad.
 *
 * The hints stay in the accessibility tree on purpose — for a blind user they are the only place
 * that says which physical key does what, since the focus ring alone cannot convey it.
 */
@Composable
fun KeyHintBar(
    hints: List<String>,
    modifier: Modifier = Modifier,
) {
    if (hints.isEmpty()) return
    val dimens = LocalDimens.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(dimens.cornerSmall))
            .background(MurmurColors.Surface.copy(alpha = 0.7f))
            .padding(
                horizontal = dimens.hintBarPaddingHorizontal,
                vertical = dimens.hintBarPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.hintBarGap),
    ) {
        hints.forEachIndexed { index, hint ->
            if (index > 0) {
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = dimens.hintFontSize),
                    color = MurmurColors.OnSurfaceMuted.copy(alpha = 0.6f),
                )
            }
            Text(
                text = hint,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = dimens.hintFontSize),
                color = MurmurColors.OnSurfaceMuted,
            )
        }
    }
}
