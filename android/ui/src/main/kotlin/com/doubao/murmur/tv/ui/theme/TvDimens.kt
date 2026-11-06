package com.doubao.murmur.tv.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing and sizing tokens for a 10-foot (TV) UI.
 *
 * Why these values are not the phone ones:
 * - **Overscan.** Televisions may crop up to 5% of every edge because of panel overscan. On a
 *   1920x1080 panel a TV reports 320 dpi, so 1 dp == 2 px and 5% of 1920 px (96 px) is 48 dp
 *   horizontally, 5% of 1080 px (54 px) is 27 dp vertically. Every screen must stay inside that
 *   inset or the first/last character of a line gets physically cut off by the bezel.
 * - **Focus targets.** A remote control focuses by D-pad, not by finger, so the 48 dp phone
 *   minimum is not enough: the viewer sits ~3 m away and needs an unmistakable target. 64 dp is
 *   the smallest we allow, and the focused element additionally scales by [focusScale].
 * - **Gaps.** Adjacent focusable elements need a visible gap, otherwise two 1.15x-scaled
 *   neighbours overlap while animating.
 */
object TvDimens {

    /** Horizontal safe area for 5% overscan (see the class KDoc for the arithmetic). */
    val overscanHorizontal = 48.dp

    /** Vertical safe area for 5% overscan. */
    val overscanVertical = 27.dp

    /** Scale applied to a focused element. 1.15x reads clearly from 3 m without clipping. */
    const val focusScale = 1.15f

    /** Border width of the focus ring. Thin borders disappear after video compression. */
    val focusBorder = 3.dp

    /** Radius of large containers (transcript card, primary action). */
    val cornerLarge = 16.dp

    /** Radius of small containers (chips, key hints, compact buttons). */
    val cornerSmall = 8.dp

    /** Minimum focusable size on TV. Deliberately larger than the 48 dp touch minimum. */
    val touchTargetMin = 64.dp

    /** Gap between two focusable neighbours, wide enough to survive the focus scale-up. */
    val keyGap = 12.dp

    /** Height of a compact action button in the right-hand grid. */
    val actionButtonHeight = 52.dp

    /** Height of the transcript card: roughly three lines of `bodyLarge` plus padding. */
    val transcriptHeight = 132.dp

    /** Side length of the round "hold to talk" primary button. */
    val micButtonSize = 104.dp

    /** Padding inside cards and buttons. */
    val contentPadding = 20.dp
}
