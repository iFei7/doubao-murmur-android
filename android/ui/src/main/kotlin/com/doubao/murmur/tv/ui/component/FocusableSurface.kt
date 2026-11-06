package com.doubao.murmur.tv.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.doubao.murmur.tv.ui.theme.MurmurColors
import com.doubao.murmur.tv.ui.theme.TvDimens

/**
 * The one focusable building block of the TV design system.
 *
 * ## Why this exists
 * A remote control has no pointer: the only way to reach a control is D-pad focus, and the only
 * way to know where you are is the focus treatment. Every interactive element in the app is
 * therefore built on this composable so that the focus ring, the scale animation and the
 * semantics are identical everywhere.
 *
 * ## Activation model
 * - `Key.DirectionCenter` (the OK button on every TV remote) and `Key.Enter` /
 *   `Key.NumPadEnter` activate on **KeyUp only**. Firing on KeyDown would activate once per
 *   auto-repeat frame while the viewer keeps the button pressed, which is how "hold OK to talk"
 *   gets silently broken by a stray click.
 * - Both the KeyDown and the KeyUp are consumed. Consuming the down is what guarantees a single
 *   activation: there is no second key handler deeper in the tree that could fire on the up.
 * - `Key.Enter` is consumed here too, because some Bluetooth remotes and every USB keyboard send
 *   `KEYCODE_ENTER` for OK. The IME service handles ENTER only when no control has focus.
 *
 * ## Why not `Modifier.clickable`
 * `clickable` installs its own D-pad key handler (it calls `onClick` on KeyUp of the same keys).
 * Keeping both would either double-fire or depend on a fragile modifier-order assumption. We use
 * `pointerInput` for pointer input (USB mouse / touchscreen on a hybrid device) and declare the
 * accessibility `onClick` action ourselves, so there is exactly one activation path per input
 * device. Pointer input is supported but never required: nothing in the TV UX depends on touch.
 *
 * ## Focus scale
 * The element scales by [TvDimens.focusScale]. Callers must keep at least [TvDimens.keyGap]
 * between neighbours, otherwise two scaled-up elements overlap while animating.
 */
@Composable
fun TvFocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(TvDimens.cornerLarge),
    contentDescription: String? = null,
    stateDescription: String? = null,
    containerColor: Color = MurmurColors.Surface,
    focusedContainerColor: Color = MurmurColors.SurfaceFocused,
    content: @Composable BoxScope.() -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused && enabled) TvDimens.focusScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "tvFocusScale",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) TvDimens.focusBorder else 1.dp,
        label = "tvFocusBorderWidth",
    )

    // The glow is drawn behind the (clipped) container, so it is applied before `clip`.
    val glow: Modifier = if (isFocused && enabled) {
        Modifier.shadow(
            elevation = 18.dp,
            shape = shape,
            clip = false,
            ambientColor = MurmurColors.Focus,
            spotColor = MurmurColors.Focus,
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(glow)
            .clip(shape)
            .background(
                if (!enabled) {
                    containerColor.copy(alpha = 0.38f)
                } else if (isFocused) {
                    focusedContainerColor
                } else {
                    containerColor
                },
            )
            .border(
                width = borderWidth,
                color = when {
                    !enabled -> MurmurColors.Outline.copy(alpha = 0.4f)
                    isFocused -> MurmurColors.Focus
                    else -> MurmurColors.Outline
                },
                shape = shape,
            )
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(onTap = { onClick() })
                }
            }
            // Focus-related modifiers must precede `focusable()` in the chain: they attach
            // themselves to the next focus target, not to the `Box` itself.
            .focusProperties { canFocus = enabled }
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { event -> handleActivationKey(enabled, event, onClick) }
            .focusable(enabled = enabled)
            .tvButtonSemantics(
                enabled = enabled,
                description = contentDescription,
                stateDesc = stateDescription,
                action = onClick,
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * Consumes the activation keys and calls [activate] exactly once, on the key **up**.
 *
 * Returning `true` for the KeyDown as well is deliberate: the event must not reach any other
 * handler (see the KDoc of [TvFocusableSurface]).
 */
private fun handleActivationKey(enabled: Boolean, event: KeyEvent, activate: () -> Unit): Boolean {
    if (!enabled) return false
    val isActivationKey = event.key == Key.DirectionCenter ||
        event.key == Key.Enter ||
        event.key == Key.NumPadEnter
    if (!isActivationKey) return false
    if (event.type == KeyEventType.KeyUp) {
        activate()
    }
    return true
}

/**
 * Accessibility contract of a focusable surface.
 *
 * Kept as a separate extension so that the parameters of the composable (`onClick`,
 * `contentDescription`, `stateDescription`) cannot shadow the equally named semantics properties
 * and silently drop the accessibility click action.
 */
private fun Modifier.tvButtonSemantics(
    enabled: Boolean,
    description: String?,
    stateDesc: String?,
    action: () -> Unit,
): Modifier = this.semantics(mergeDescendants = true) {
    role = Role.Button
    description?.let { contentDescription = it }
    stateDesc?.let { stateDescription = it }
    if (enabled) {
        onClick(label = null) {
            action()
            true
        }
    }
}
