package com.doubao.murmur.tv.ime

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AbstractComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * The root view of the IME window.
 *
 * ## Why a plain `ComposeView` cannot be used
 * Inside an `InputMethodService` there is no `Activity`, hence no
 * `ViewTreeLifecycleOwner` / `ViewTreeViewModelStoreOwner` / `ViewTreeSavedStateRegistryOwner`.
 * Compose needs all three:
 * - the **lifecycle owner** drives the window recomposer (`LaunchedEffect` starts only once the
 *   owner is at least `CREATED`, and the recomposer pauses when the IME window is hidden),
 * - the **view model store owner** backs `viewModel()` / `rememberSaveable`'s `SavedStateHandle`,
 * - the **saved state registry owner** backs `rememberSaveable` and `saveable`.
 *
 * Without them `LaunchedEffect` throws "CompositionLocal LocalLifecycleOwner not present" and
 * `rememberSaveable` fails while creating the saveable registry.
 *
 * ## Attach / detach semantics
 * An IME input view is attached and detached every time the keyboard is shown and hidden, many
 * times per app session. The owners therefore follow the *view*:
 * - attach → `RESUMED`,
 * - detach → `CREATED` (the composition is disposed, but ViewModels survive, which is what we
 *   want: the recogniser does not restart),
 * - [dispose] (from `onDestroy` of the service) → `DESTROYED`, once, guarded by a flag so a
 *   double `onDetachedFromWindow`/`onDestroy` cannot push a destroyed registry around.
 *
 * ## Usage
 * ```kotlin
 * val view = ImeComposeView(context).apply { content = { MurmurTvTheme { ... } } }
 * ```
 * `content` is a property rather than an abstract member so the service can pass a lambda that
 * captures the controller without this class knowing anything about it.
 */
class ImeComposeView(context: Context) : AbstractComposeView(context) {

    private val owners = ImeViewTreeOwners()

    /** The Compose content of the keyboard. Set before returning the view from `onCreateInputView`. */
    var content: @Composable () -> Unit = {}

    init {
        // Install the owners on this view; descendants (and the recomposer) resolve them through
        // the view tree.
        setViewTreeLifecycleOwner(owners)
        setViewTreeViewModelStoreOwner(owners)
        setViewTreeSavedStateRegistryOwner(owners)
        // Compose measures the keyboard before the window is attached (the framework asks for
        // `onComputeInsets` right away), so the lifecycle must already be past INITIALIZED.
        owners.markAttached()
    }

    @Composable
    override fun Content() {
        content()
    }

    override fun onAttachedToWindow() {
        // Move to RESUMED *before* super: `AbstractComposeView` installs the window recomposer
        // inside `super.onAttachedToWindow()`, and it must observe a live owner.
        owners.markAttached()
        super.onAttachedToWindow()
        // The pre-attach measure was short-circuited by [ImeComposeContainer] (see its KDoc);
        // now that the view is attached, request the real pass so the composition is created
        // through `super.onMeasure`. `isAttachedToWindow` is already true at this point, so the
        // follow-up measure goes through `super` and creates the composition. This runs once per
        // window binding — it cannot loop, because attach only happens once per
        // `WindowManager.addView` and this requestLayout finds the view attached.
        requestLayout()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        owners.markDetached()
    }

    /**
     * Tears the owners down. Called from [DoubaoVoiceInputMethodService.onDestroy]; not from
     * `onDetachedFromWindow`, because the same view instance is reused when the keyboard comes
     * back.
     */
    fun dispose() {
        owners.dispose()
    }
}

/**
 * Height reported by [ImeComposeContainer.onMeasure] before the container is attached. Small and
 * non-zero: zero would trip framework assertions on empty views, and the real size arrives with
 * the post-attach layout pass.
 */
private const val PLACEHOLDER_HEIGHT_PX = 1

/**
 * Outer wrapper that shields [ImeComposeView] from the IME framework's pre-attach measure pass.
 *
 * ## Why this wrapper exists at all
 * `AbstractComposeView.onMeasure` (compose-ui 1.7.5) unconditionally calls
 * `ensureCompositionCreated()`, which resolves the parent composition context and, finding none,
 * calls `WindowRecomposer_androidKt.getWindowRecomposer(view)`. That function throws
 * `IllegalStateException("Cannot locate windowRecomposer; View is not attached to a window")`
 * when `view.isAttachedToWindow` is false. The IME framework measures the input view *before*
 * `WindowManager.addView` finishes (`InputMethodService.setInputView()` → measure → attach), so
 * the first measure pass on the compose view always runs pre-attach — and crashes, which is why
 * the keyboard never drew and the process death/rebind loop occurred.
 *
 * Normally one would override `onMeasure` on the compose view itself to short-circuit that pass,
 * but `AbstractComposeView.onMeasure` is **final** in compose-ui 1.7.5, so the interception has
 * to happen one level up: on an outer `FrameLayout` wrapping the compose view, where `onMeasure`
 * is not final.
 *
 * ## Measure semantics
 * - **Not attached:** skip `super.onMeasure` entirely — `FrameLayout`'s implementation calls
 *   `measureChildWithMargins` on every child, which would reach the compose view and trigger the
 *   crash we are shielding against. Instead report a conservative size directly: the width
 *   resolved from the spec (the IME window is screen-wide, so a normal pass yields
 *   AT_MOST/EXACTLY and resolves to a real width) and [PLACEHOLDER_HEIGHT_PX].
 * - **Attached:** delegate to `super`, which measures the compose view normally and creates the
 *   composition. [onAttachedToWindow] calls [requestLayout] so the real pass happens as soon as
 *   the view tree lands in the IME window, replacing the placeholder size.
 */
class ImeComposeContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!isAttachedToWindow) {
            // Do NOT call super here: it would measure children, reaching the compose view while
            // it is still detached and triggering the windowRecomposer crash. Deliberately
            // bypasses measureChildren/measureChildWithMargins entirely.
            val width = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec).coerceAtLeast(1)
            val height = resolveSize(PLACEHOLDER_HEIGHT_PX, heightMeasureSpec).coerceAtLeast(1)
            setMeasuredDimension(width, height)
            return
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The pre-attach pass stored a placeholder size; now that the container (and its compose
        // child) is attached, request a fresh layout pass so the real measurement — through
        // `super.onMeasure` and into the composition — runs. This fires once per window binding
        // and cannot loop: attach only happens once per `WindowManager.addView`, and this
        // requestLayout finds the view already attached.
        requestLayout()
    }
}

/**
 * Holds the three AndroidX owners that Compose expects to find on the view tree.
 *
 * Not a `ComponentActivity`: an `InputMethodService` has no activity window, and pulling in
 * `lifecycle-service`'s `LifecycleService` would tie the owners to the service lifetime instead of
 * the *view* lifetime, which is the opposite of what the IME needs.
 */
private class ImeViewTreeOwners : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = registry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    var isDisposed: Boolean = false
        private set

    init {
        // `performRestore(Bundle())` must happen before the lifecycle reaches CREATED. An empty
        // bundle is correct here: an IME has nothing to restore, it only needs the registry to
        // exist so that `rememberSaveable` has somewhere to write to.
        savedStateController.performAttach()
        savedStateController.performRestore(Bundle())
    }

    fun markAttached() {
        if (isDisposed) return
        if (registry.currentState != Lifecycle.State.RESUMED) {
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    fun markDetached() {
        if (isDisposed) return
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            registry.currentState = Lifecycle.State.CREATED
        }
    }

    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        // INITIALIZED -> DESTROYED would be an invalid transition, and a registry that never
        // reached CREATED has nothing to tear down.
        if (registry.currentState != Lifecycle.State.INITIALIZED) {
            registry.currentState = Lifecycle.State.DESTROYED
        }
        viewModelStore.clear()
    }
}
