package com.doubao.murmur.tv.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Spacing and sizing tokens of the design system.
 *
 * The app ships a single phone form factor, so one immutable instance ([TvDimens]) covers every
 * screen and [MurmurTvTheme] publishes it through [LocalDimens]. Values follow the phone
 * conventions:
 * - no overscan inset (a phone has no bezel cropping);
 * - no focus scale (touch input has no D-pad focus animation);
 * - sizes tighten toward the 48 dp Material touch minimum.
 *
 * The top-level names ([MurmurDimens], [TvDimens], [LocalDimens]) are part of the API consumed by
 * the `:ime` module and must not be renamed, even though the "TV" prefix is historical.
 */
data class MurmurDimens(
    /** Horizontal safe area inset. Zero on a phone. */
    val overscanHorizontal: Dp,

    /** Vertical safe area inset. Zero on a phone. */
    val overscanVertical: Dp,

    /** Scale applied to a focused element. 1x on touch input. */
    val focusScale: Float,

    /** Border width of the focus ring. */
    val focusBorder: Dp,

    /** Radius of large containers (transcript card, primary action). */
    val cornerLarge: Dp,

    /** Radius of small containers (chips, key hints, compact buttons). */
    val cornerSmall: Dp,

    /** Minimum touch target (Material guideline). */
    val touchTargetMin: Dp,

    /** Gap between two neighbouring interactive elements. */
    val keyGap: Dp,

    /** Height of a compact action button. */
    val actionButtonHeight: Dp,

    /** Height of the transcript card: roughly three lines of `bodyLarge` plus padding. */
    val transcriptHeight: Dp,

    /** Side length of the round "hold to talk" primary button. */
    val micButtonSize: Dp,

    /** Padding inside cards and buttons. */
    val contentPadding: Dp,

    /** Horizontal content edge for scrolling screens (standard Material margin). */
    val contentEdge: Dp,

    /** Left/right padding inside the bottom key-hint bar. */
    val hintBarPaddingHorizontal: Dp,

    /** Top/bottom padding inside the bottom key-hint bar. */
    val hintBarPaddingVertical: Dp,

    /** Gap between two hints inside the bottom key-hint bar. */
    val hintBarGap: Dp,

    /** Font size of the key hints. */
    val hintFontSize: TextUnit,
)

/**
 * The single phone token set.
 *
 * Kept as a top-level `val` rather than an `object` on purpose: existing `TvDimens.xxx` call sites
 * - including every one in the :ime module - resolve to the exact same property access.
 */
val TvDimens = MurmurDimens(
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
    micButtonSize = 56.dp,
    contentPadding = 12.dp,
    contentEdge = 16.dp,
    hintBarPaddingHorizontal = 12.dp,
    hintBarPaddingVertical = 8.dp,
    hintBarGap = 12.dp,
    hintFontSize = 14.sp,
)

/**
 * Current [MurmurDimens] for the composition. Defaults to [TvDimens] so a composable rendered
 * outside [MurmurTvTheme] (or before the theme is applied) still gets the phone values.
 */
val LocalDimens = staticCompositionLocalOf { TvDimens }
