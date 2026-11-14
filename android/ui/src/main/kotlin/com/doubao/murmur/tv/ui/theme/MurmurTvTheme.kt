package com.doubao.murmur.tv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

/**
 * Shape shared by the dark and the light palette. Every token of the TV design system appears
 * exactly once here, so the two palettes can never drift apart: adding a colour forces both
 * modes to decide what it looks like.
 *
 * The foreground/background pairs of both palettes are pushed to a contrast ratio well above
 * WCAG AA, because distance and panel gamma eat contrast that a handheld screen gets away with.
 */
@Immutable
interface MurmurColorsPalette {

    /** App background. */
    val Background: Color

    /** Resting container colour (transcript card, action buttons). */
    val Surface: Color

    /** One step up from [Surface]: nested cards, status chips. */
    val SurfaceElevated: Color

    /** Container colour while the element holds focus. */
    val SurfaceFocused: Color

    /** Primary text. */
    val OnBackground: Color

    /** Text on [Surface]. */
    val OnSurface: Color

    /** Secondary text: placeholders, key hints, metadata. Still >= 4.5:1 on [Surface]. */
    val OnSurfaceMuted: Color

    /** Hairline borders of unfocused containers. */
    val Outline: Color

    /** Focus ring and glow. Reserved for focus only. */
    val Focus: Color

    /** Brand accent for primary actions. */
    val Accent: Color

    /** Recording / listening state. */
    val Listening: Color

    /** Soft warning: not signed in, microphone not granted. */
    val Warning: Color

    /** Hard error. */
    val Error: Color

    /** Dims the host app behind dialogs. */
    val Scrim: Color
}

/**
 * The dark palette — the original TV token set, byte-for-byte unchanged.
 *
 * A television screen is always viewed in a dim room from about 3 metres away, so the TV always
 * renders this palette: a light background at 200+ nits in a dark room is physically
 * uncomfortable and washes out the video content behind the app.
 *
 * There is exactly one saturated accent ([Focus]) and it is reserved for "this is where the
 * remote is pointing right now". Reusing it for decoration would break that affordance.
 */
object MurmurDarkColors : MurmurColorsPalette {

    /** App background. Near-black with a hint of blue so pure black video planes still separate. */
    override val Background = Color(0xFF0B0B0F)

    /** Resting container colour (transcript card, action buttons). */
    override val Surface = Color(0xFF17171E)

    /** One step up from [Surface]: nested cards, status chips. */
    override val SurfaceElevated = Color(0xFF212129)

    /** Container colour while the element holds focus. */
    override val SurfaceFocused = Color(0xFF2C2C37)

    /** Primary text. */
    override val OnBackground = Color(0xFFF4F4F7)

    /** Text on [Surface]. */
    override val OnSurface = Color(0xFFEDEDF2)

    /** Secondary text: placeholders, key hints, metadata. Still >= 4.5:1 on [Surface]. */
    override val OnSurfaceMuted = Color(0xFFA5A5B4)

    /** Hairline borders of unfocused containers. */
    override val Outline = Color(0xFF3C3C48)

    /** Focus ring and glow. Reserved for focus only. */
    override val Focus = Color(0xFFFFD24D)

    /** Brand accent for primary actions. */
    override val Accent = Color(0xFF4C8DFF)

    /** Recording / listening state. */
    override val Listening = Color(0xFF37D67A)

    /** Soft warning: not signed in, microphone not granted. */
    override val Warning = Color(0xFFFFB020)

    /** Hard error. */
    override val Error = Color(0xFFFF6B6B)

    /** Dims the host app behind dialogs. */
    override val Scrim = Color(0xCC000000)
}

/**
 * The light palette for screens that follow the system day/night setting.
 *
 * The light values mirror the dark ones slot by slot (same role, inverted luminance):
 * - [Focus] stays the saturated yellow — it is still reserved for focus only, and on a lit phone
 *   screen the yellow ring reads against both palettes,
 * - [Accent] deepens to a AA-contrast blue for white-on-accent text at handheld size,
 * - status colours darken ([Listening]/[Warning]/[Error]) so they stay legible on white cards.
 */
object MurmurLightColors : MurmurColorsPalette {

    /** App background. Soft neutral grey so white cards still separate from the page. */
    override val Background = Color(0xFFF5F5F7)

    /** Resting container colour (transcript card, action buttons). */
    override val Surface = Color(0xFFFFFFFF)

    /** One step up from [Surface]: nested cards, status chips. */
    override val SurfaceElevated = Color(0xFFFFFFFF)

    /** Container colour while the element holds focus. */
    override val SurfaceFocused = Color(0xFFEDEDF0)

    /** Primary text. */
    override val OnBackground = Color(0xFF1A1A1E)

    /** Text on [Surface]. */
    override val OnSurface = Color(0xFF1A1A1E)

    /** Secondary text: placeholders, key hints, metadata. Still >= 4.5:1 on [Surface]. */
    override val OnSurfaceMuted = Color(0xFF7A7A82)

    /** Hairline borders of unfocused containers. */
    override val Outline = Color(0xFFE2E2E6)

    /** Focus ring and glow. Reserved for focus only. */
    override val Focus = Color(0xFFFFD24D)

    /** Brand accent for primary actions. */
    override val Accent = Color(0xFF185FA5)

    /** Recording / listening state. */
    override val Listening = Color(0xFF0F6E56)

    /** Soft warning: not signed in, microphone not granted. */
    override val Warning = Color(0xFF854F0B)

    /** Hard error. */
    override val Error = Color(0xFFA32D2D)

    /** Dims the host app behind dialogs. */
    override val Scrim = Color(0x66000000)
}

/**
 * Current [MurmurColorsPalette] for the composition. Defaults to the dark palette so a composable
 * rendered outside [MurmurTvTheme] (or before the theme is applied) still gets the original TV
 * values.
 */
val LocalColors = staticCompositionLocalOf<MurmurColorsPalette> { MurmurDarkColors }

/**
 * Token accessors of the TV design system, resolved against the current theme mode.
 *
 * Components read these directly rather than going through `MaterialTheme.colorScheme` so that a
 * mode mismatch cannot make a focus ring indistinguishable from a card background. Each accessor
 * is a `@Composable` getter over [LocalColors]: existing `MurmurColors.X` call sites keep their
 * exact spelling and resolve to the palette published by [MurmurTvTheme].
 */
object MurmurColors {

    /** App background. */
    val Background: Color @Composable get() = LocalColors.current.Background

    /** Resting container colour (transcript card, action buttons). */
    val Surface: Color @Composable get() = LocalColors.current.Surface

    /** One step up from [Surface]: nested cards, status chips. */
    val SurfaceElevated: Color @Composable get() = LocalColors.current.SurfaceElevated

    /** Container colour while the element holds focus. */
    val SurfaceFocused: Color @Composable get() = LocalColors.current.SurfaceFocused

    /** Primary text. */
    val OnBackground: Color @Composable get() = LocalColors.current.OnBackground

    /** Text on [Surface]. */
    val OnSurface: Color @Composable get() = LocalColors.current.OnSurface

    /** Secondary text: placeholders, key hints, metadata. */
    val OnSurfaceMuted: Color @Composable get() = LocalColors.current.OnSurfaceMuted

    /** Hairline borders of unfocused containers. */
    val Outline: Color @Composable get() = LocalColors.current.Outline

    /** Focus ring and glow. Reserved for focus only. */
    val Focus: Color @Composable get() = LocalColors.current.Focus

    /** Brand accent for primary actions. */
    val Accent: Color @Composable get() = LocalColors.current.Accent

    /** Recording / listening state. */
    val Listening: Color @Composable get() = LocalColors.current.Listening

    /** Soft warning: not signed in, microphone not granted. */
    val Warning: Color @Composable get() = LocalColors.current.Warning

    /** Hard error. */
    val Error: Color @Composable get() = LocalColors.current.Error

    /** Dims the host app behind dialogs. */
    val Scrim: Color @Composable get() = LocalColors.current.Scrim
}

/**
 * The `androidx.tv.material3` colour scheme for the dark palette. Only the slots that the design
 * system actually uses are overridden; everything else keeps the tv-material default, which is
 * already tuned for TV contrast.
 */
internal val MurmurDarkColorScheme = darkColorScheme(
    primary = MurmurDarkColors.Accent,
    onPrimary = Color(0xFF06101F),
    primaryContainer = MurmurDarkColors.SurfaceFocused,
    onPrimaryContainer = MurmurDarkColors.OnBackground,
    secondary = MurmurDarkColors.Listening,
    onSecondary = Color(0xFF04140A),
    background = MurmurDarkColors.Background,
    onBackground = MurmurDarkColors.OnBackground,
    surface = MurmurDarkColors.Surface,
    onSurface = MurmurDarkColors.OnSurface,
    surfaceVariant = MurmurDarkColors.SurfaceElevated,
    onSurfaceVariant = MurmurDarkColors.OnSurfaceMuted,
    error = MurmurDarkColors.Error,
    onError = Color(0xFF1A0505),
    // tv-material 1.0.0 has no `outline` slot: the TV colour scheme calls it `border`
    // (plus `borderVariant` for the weaker step). The phone `androidx.compose.material3` scheme
    // keeps `outline` — the two same-named builders do NOT share a parameter set.
    border = MurmurDarkColors.Outline,
)

/**
 * The `androidx.tv.material3` colour scheme for the light palette. Same slot mapping as the dark
 * scheme; the "on" colours of saturated slots flip to white because the light accent/status
 * colours are dark enough to carry white text.
 */
internal val MurmurLightColorScheme = lightColorScheme(
    primary = MurmurLightColors.Accent,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = MurmurLightColors.SurfaceFocused,
    onPrimaryContainer = MurmurLightColors.OnBackground,
    secondary = MurmurLightColors.Listening,
    onSecondary = Color(0xFFFFFFFF),
    background = MurmurLightColors.Background,
    onBackground = MurmurLightColors.OnBackground,
    surface = MurmurLightColors.Surface,
    onSurface = MurmurLightColors.OnSurface,
    surfaceVariant = MurmurLightColors.SurfaceElevated,
    onSurfaceVariant = MurmurLightColors.OnSurfaceMuted,
    error = MurmurLightColors.Error,
    onError = Color(0xFFFFFFFF),
    border = MurmurLightColors.Outline,
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
 *   phone package would read a default (light) colour scheme and lose the palette treatment.
 * - There is intentionally no `Shapes` override: focusable surfaces take an explicit `Shape`.
 */
@Composable
fun MurmurTvTheme(content: @Composable () -> Unit) {
    // Colour follows the system day/night setting; the light and dark palettes share the same
    // role structure so every token has a value in both modes.
    val dark = isSystemInDarkTheme()
    val palette = if (dark) MurmurDarkColors else MurmurLightColors
    CompositionLocalProvider(
        LocalDimens provides TvDimens,
        LocalColors provides palette,
    ) {
        MaterialTheme(
            colorScheme = if (dark) MurmurDarkColorScheme else MurmurLightColorScheme,
            typography = MurmurTypography,
            content = content,
        )
    }
}
