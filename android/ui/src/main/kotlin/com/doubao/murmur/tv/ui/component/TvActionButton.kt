package com.doubao.murmur.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.doubao.murmur.tv.ui.theme.LocalDimens
import com.doubao.murmur.tv.ui.theme.MurmurColors

/**
 * Icon + label action button, sized for a D-pad.
 *
 * Design notes:
 * - Minimum height is the active `touchTargetMin` token: 64 dp on TV (D-pad from 3 m), 48 dp on a
 *   phone. A small D-pad target is a usability bug on the sofa, not a nit.
 * - [selected] does not change the focus treatment (that is owned by
 *   [TvFocusableSurface]); it only tints the inner pill, so "which commit mode is active" stays
 *   readable while the focus ring moves elsewhere.
 * - The icon is decorative (`contentDescription = null`): the label is merged into the parent
 *   semantics node, so TalkBack reads "提交" once instead of "提交 提交".
 */
@Composable
fun TvActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    contentDescription: String? = null,
    containerColor: Color = MurmurColors.Surface,
) {
    val dimens = LocalDimens.current
    // 88 dp min width is a D-pad target statement; on a 360 dp phone it only truncates labels,
    // so the touch path relaxes it (weight/adaptive parents size the button instead) and allows
    // a second line. TV keeps the original single-line 88 dp pill verbatim.
    val compact = LocalConfiguration.current.smallestScreenWidthDp < 600
    TvFocusableSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = dimens.touchTargetMin),
        enabled = enabled,
        shape = RoundedCornerShape(dimens.cornerSmall),
        contentDescription = contentDescription,
        containerColor = containerColor,
    ) {
        Row(
            modifier = Modifier
                .padding(4.dp)
                .clip(RoundedCornerShape(dimens.cornerSmall))
                .background(
                    if (selected) MurmurColors.Accent.copy(alpha = 0.28f) else Color.Transparent,
                )
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .widthIn(min = if (compact) 64.dp else 88.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = when {
                !enabled -> MurmurColors.OnSurfaceMuted.copy(alpha = 0.5f)
                selected -> MurmurColors.Accent
                else -> MurmurColors.OnSurface
            }
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = tint,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = tint,
                maxLines = if (compact) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
