package com.doubao.murmur.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.doubao.murmur.tv.ui.theme.MurmurColors
import com.doubao.murmur.tv.ui.theme.TvDimens

/** Semantic tone of a [TvStatusChip]. The tone never carries information on its own, only colour. */
enum class TvChipTone {
    /** Neutral fact: "已登录". */
    Neutral,

    /** Positive, live state: "录音中". */
    Active,

    /** Needs the viewer to do something: "未授权麦克风". */
    Warning,
}

/**
 * Small, non-interactive status label ("已登录" / "未授权麦克风" / "录音中").
 *
 * - Not focusable: status must never interrupt D-pad navigation.
 * - The text is the whole meaning, the colour is only reinforcement, so colour-blind viewers and
 *   TalkBack get the same information. [contentDescription] exists for the rare case where the
 *   visible label is abbreviated.
 * - Chips are laid out with [TvDimens.keyGap] between them by the caller; this component adds no
 *   outer spacing of its own.
 */
@Composable
fun TvStatusChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: TvChipTone = TvChipTone.Neutral,
    contentDescription: String? = null,
) {
    val (background, foreground) = when (tone) {
        TvChipTone.Neutral -> MurmurColors.SurfaceElevated to MurmurColors.OnSurfaceMuted
        TvChipTone.Active -> MurmurColors.Listening.copy(alpha = 0.18f) to MurmurColors.Listening
        TvChipTone.Warning -> MurmurColors.Warning.copy(alpha = 0.18f) to MurmurColors.Warning
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(TvDimens.cornerSmall))
            .background(background)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription?.let { this.contentDescription = it }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = foreground,
        )
    }
}
