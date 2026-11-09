package com.doubao.murmur.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/**
 * Colour tokens of the TV design system.
 *
 * A television screen is always viewed in a dim room from about 3 metres away, so:
 * - the palette is **dark only** (a light background at 200+ nits in a dark room is physically
 *   uncomfortable and washes out the video content behind the app),
 * - the foreground/background pairs are pushed to a contrast ratio well above WCAG AA, because
 *   distance and panel gamma eat contrast that a phone would get away with,
 * - there is exactly one saturated accent ([Focus]) and it is reserved for "this is where the
 *   remote is pointing right now". Reusing it for decoration would break that affordance.
 *
 * Components read these constants directly rather than going through `MaterialTheme.colorScheme`
 * so that a future theme (e.g. a light "shop demo" mode) cannot accidentally make a focus ring
 * indistinguishable from a card background.
 */
object MurmurColors {

    /** App background. Near-black with a hint of blue so pure black video planes still separate. */
    val Background = Color(0xFF0B0B0F)

    /** Resting container colour (transcript card, action buttons). */
    val Surface = Color(0xFF17171E)

    /** One step up from [Surface]: nested cards, status chips. */
    val SurfaceElevated = Color(0xFF212129)

    /** Container colour while the element holds focus. */
    val SurfaceFocused = Color(0xFF2C2C37)

    /** Primary text. */
    val OnBackground = Color(0xFFF4F4F7)

    /** Text on [Surface]. */
    val OnSurface = Color(0xFFEDEDF2)

    /** Secondary text: placeholders, key hints, metadata. Still >= 4.5:1 on [Surface]. */
    val OnSurfaceMuted = Color(0xFFA5A5B4)

    /** Hairline borders of unfocused containers. */
    val Outline = Color(0xFF3C3C48)

    /** Focus ring and glow. Reserved for focus only. */
    val Focus = Color(0xFFFFD24D)

    /** Brand accent for primary actions. */
    val Accent = Color(0xFF4C8DFF)

    /** Recording / listening state. */
    val Listening = Color(0xFF37D67A)

    /** Soft warning: not signed in, microphone not granted. */
    val Warning = Color(0xFFFFB020)

    /** Hard error. */
    val Error = Color(0xFFFF6B6B)

    /** Dims the host app behind dialogs. */
    val Scrim = Color(0xCC000000)
}

/**
 * The single colour scheme of the app. Only the slots that the design system actually uses are
 * overridden; everything else keeps the `androidx.tv.material3` default, which is already tuned
 * for TV contrast.
 */
internal val MurmurColorScheme = darkColorScheme(
    primary = MurmurColors.Accent,
    onPrimary = Color(0xFF06101F),
    primaryContainer = MurmurColors.SurfaceFocused,
    onPrimaryContainer = MurmurColors.OnBackground,
    secondary = MurmurColors.Listening,
    onSecondary = Color(0xFF04140A),
    background = MurmurColors.Background,
    onBackground = MurmurColors.OnBackground,
    surface = MurmurColors.Surface,
    onSurface = MurmurColors.OnSurface,
    surfaceVariant = MurmurColors.SurfaceElevated,
    onSurfaceVariant = MurmurColors.OnSurfaceMuted,
    error = MurmurColors.Error,
    onError = Color(0xFF1A0505),
    // tv-material 1.0.0 has no `outline` slot: the TV colour scheme calls it `border`
    // (plus `borderVariant` for the weaker step). The phone `androidx.compose.material3` scheme
    // keeps `outline` — the two same-named builders do NOT share a parameter set.
    border = MurmurColors.Outline,
)

/**
 * Type scale for 10-foot viewing.
 *
 * `androidx.tv.material3.Typography` already ships larger defaults than the phone scale; we only
 * bump the three styles that the keyboard actually uses so that a two-character Chinese word is
 * legible from the sofa. Sizes are in `sp` (TV system font scale is 1.0 in practice, and the
 * `sp` unit keeps accessibility font scaling working).
 */
internal val MurmurTypography: Typography = Typography().copy(
    headlineSmall = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
)

/**
 * Theme root for every screen in the app, including the IME window and its settings activity.
 *
 * Notes for callers:
 * - The components come from `androidx.tv.material3`, never from `androidx.compose.material3`.
 *   Mixing the two would create two independent `MaterialTheme` domains: a `Button` from the
 *   phone package would read a default (light) colour scheme and lose the TV focus treatment.
 * - There is intentionally no `Shapes` override: focusable surfaces take an explicit `Shape`
 *   so that a 1.15x focus scale never distorts the corner radius.
 */
@Composable
fun MurmurTvTheme(content: @Composable () -> Unit) {
    // One APK adapts to both form factors without a second material3 theme domain: below 600 dp of
    // smallest width (a portrait phone) publish the compact token set, otherwise keep the original
    // 10-foot values. The tv-material colour scheme and typography stay identical for both.
    val compact = LocalConfiguration.current.smallestScreenWidthDp < 600
    CompositionLocalProvider(LocalDimens provides if (compact) PhoneDimens else TvDimens) {
        MaterialTheme(
            colorScheme = MurmurColorScheme,
            typography = MurmurTypography,
            content = content,
        )
    }
}
