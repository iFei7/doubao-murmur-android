package com.doubao.murmur.tv.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing and sizing tokens of the design system.
 *
 * Two immutable instances are provided: [TvDimens] for a 10-foot (TV) UI and [PhoneDimens] for a
 * compact portrait phone. [MurmurTvTheme] publishes the matching one through [LocalDimens], so a
 * single APK can adapt without a build flavor or a second theme domain.
 *
 * Why the TV values are not the phone ones:
 * - **Overscan.** Televisions may crop up to 5% of every edge because of panel overscan. On a
 *   1920x1080 panel a TV reports 320 dpi, so 1 dp == 2 px and 5% of 1920 px (96 px) is 48 dp
 *   horizontally, 5% of 1080 px (54 px) is 27 dp vertically. Every screen must stay inside that
 *   inset or the first/last character of a line gets physically cut off by the bezel.
 * - **Focus targets.** A remote control focuses by D-pad, not by finger, so the 48 dp phone
 *   minimum is not enough: the viewer sits ~3 m away and needs an unmistakable target. 64 dp is
 *   the smallest we allow, and the focused element additionally scales by [focusScale].
 * - **Gaps.** Adjacent focusable elements need a visible gap, otherwise two 1.15x-scaled
 *   neighbours overlap while animating.
 *
 * [PhoneDimens] zeroes the overscan (a phone has no bezel cropping), drops the focus scale (touch
 * input has no D-pad focus animation) and tightens every size toward the 48 dp touch minimum.
 */
data class MurmurDimens(
    /** Horizontal safe area for 5% overscan (see the class KDoc for the arithmetic). */
    val overscanHorizontal: Dp,

    /** Vertical safe area for 5% overscan. */
    val overscanVertical: Dp,

    /** Scale applied to a focused element. 1.15x reads clearly from 3 m without clipping. */
    val focusScale: Float,

    /** Border width of the focus ring. Thin borders disappear after video compression. */
    val focusBorder: Dp,

    /** Radius of large containers (transcript card, primary action). */
    val cornerLarge: Dp,

    /** Radius of small containers (chips, key hints, compact buttons). */
    val cornerSmall: Dp,

    /** Minimum focusable size on TV. Deliberately larger than the 48 dp touch minimum. */
    val touchTargetMin: Dp,

    /** Gap between two focusable neighbours, wide enough to survive the focus scale-up. */
    val keyGap: Dp,

    /** Height of a compact action button in the right-hand grid. */
    val actionButtonHeight: Dp,

    /** Height of the transcript card: roughly three lines of `bodyLarge` plus padding. */
    val transcriptHeight: Dp,

    /** Side length of the round "hold to talk" primary button. */
    val micButtonSize: Dp,

    /** Padding inside cards and buttons. */
    val contentPadding: Dp,
)

/**
 * The original 10-foot token set.
 *
 * Kept as a top-level `val` rather than an `object` on purpose: existing `TvDimens.xxx` call sites
 * - including every one in the :ime module, which is not part of this adaptation - resolve to the
 * exact same property access and keep the exact same values. TV behaviour is unchanged.
 */
val TvDimens = MurmurDimens(
    overscanHorizontal = 48.dp,
    overscanVertical = 27.dp,
    focusScale = 1.15f,
    focusBorder = 3.dp,
    cornerLarge = 16.dp,
    cornerSmall = 8.dp,
    touchTargetMin = 64.dp,
    keyGap = 12.dp,
    actionButtonHeight = 52.dp,
    transcriptHeight = 132.dp,
    micButtonSize = 104.dp,
    contentPadding = 20.dp,
)

/** Compact portrait-phone token set, published by [MurmurTvTheme] on screens below 600 dp. */
val PhoneDimens = MurmurDimens(
    overscanHorizontal = 0.dp,
    overscanVertical = 0.dp,
    focusScale = 1f,
    focusBorder = 2.dp,
    cornerLarge = 12.dp,
    cornerSmall = 8.dp,
    touchTargetMin = 48.dp,
    keyGap = 8.dp,
    actionButtonHeight = 44.dp,
    transcriptHeight = 96.dp,
    micButtonSize = 72.dp,
    contentPadding = 12.dp,
)

/**
 * Current [MurmurDimens] for the composition. Defaults to [TvDimens] so a composable rendered
 * outside [MurmurTvTheme] (or before the theme is applied) still gets the original 10-foot values.
 */
val LocalDimens = staticCompositionLocalOf { TvDimens }
