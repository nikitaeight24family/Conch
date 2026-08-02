package ai.eight24family.conch.ui.window

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.window.core.layout.WindowSizeClass

/**
 * App-wide adaptive context that every composable can read via
 * [LocalAppWindowAdaptive]. Phase 0 of the foldable workstream — at this
 * stage NOTHING in the UI tree actually branches on these flags yet; later
 * phases plug in `ListDetailPaneScaffold` / `SupportingPaneScaffold` /
 * fold-aware layouts and read this provider.
 *
 * Sources, in order of authority:
 *
 *  1. [androidx.compose.material3.adaptive.WindowAdaptiveInfo] from
 *     `currentWindowAdaptiveInfo()` — provides the window size class
 *     (compact / medium / expanded) and the fold posture (tabletop /
 *     book / flat) computed from `androidx.window`'s `WindowInfoTracker`
 *     flow. Recomposes automatically when the window changes shape (fold
 *     opens / closes, split-screen resize, DeX window drag, rotation).
 *
 *  2. [Configuration.uiMode] — detects DeX / desktop mode. Samsung DeX
 *     reports `UI_MODE_TYPE_DESK` so we don't need their proprietary SDK;
 *     same flag covers Android Auto's car desktop and any Chromebook
 *     desktop launcher.
 *
 *  3. [Activity.isInPictureInPictureMode] — read defensively via
 *     `LocalContext.findActivity()`; null when host context isn't an
 *     Activity (preview, tests, hypothetical embedded usage). Treated as
 *     `false` in those cases.
 *
 * The three fold postures we care about (see Material 3 adaptive docs):
 *
 *  - [FoldPosture.FLAT] — fully open or fully closed; pretend the hinge
 *    isn't there.
 *  - [FoldPosture.TABLETOP] — half-folded with the hinge HORIZONTAL
 *    (laptop posture, book on a table). UI should split top / bottom so
 *    nothing crosses the hinge. **This is the marquee fold feature for
 *    the chat surface** — chat above the hinge, input below.
 *  - [FoldPosture.BOOK] — half-folded with the hinge VERTICAL. UI should
 *    split left / right of the hinge (think the spine of a paperback).
 *
 * Avoid duplicating these checks in scaffolds — derive everything from
 * the single [AppWindowAdaptive] instance we put in CompositionLocal.
 */
data class AppWindowAdaptive(
    val windowSizeClass: WindowSizeClass,
    val posture: FoldPosture,
    val isInDex: Boolean,
    val isInPip: Boolean,
) {

    /** True when the window is phone-narrow (<600dp). Compact = stack nav. */
    val isCompact: Boolean
        get() = !windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    /** True at ≥600dp width — switch nav to NavigationRail, enable two-pane. */
    val isMediumOrWider: Boolean
        get() = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    /** True at ≥840dp width — enable three-pane layout (servers | sessions | chat). */
    val isExpanded: Boolean
        get() = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    /** True when fold posture is anything other than fully flat. */
    val isFoldHalfOpen: Boolean
        get() = posture != FoldPosture.FLAT

    companion object {
        /**
         * Sane fallback used when no real provider is in scope (Composable
         * previews, unit tests that forget to wrap, etc). Treats the
         * window as a normal phone — no folds, no DeX, no PiP.
         */
        // Direct construction (minWidth=0, minHeight=0) yields the Compact
        // bucket — anything with at least 0dp width matches, and the class
        // sorts to the bottom of the breakpoint ladder. This is the
        // canonical "phone fallback" that downstream branches will read.
        val Compact = AppWindowAdaptive(
            windowSizeClass = WindowSizeClass(minWidthDp = 0, minHeightDp = 0),
            posture = FoldPosture.FLAT,
            isInDex = false,
            isInPip = false,
        )
    }
}

/** See KDoc on [AppWindowAdaptive] for the meaning of each posture. */
enum class FoldPosture { FLAT, TABLETOP, BOOK }

/**
 * Composition local that carries the per-window adaptive context. Default
 * is a phone-shaped [AppWindowAdaptive.Compact] so previews & tests render
 * without crashing — production composables get the live value via
 * [AppWindowAdaptiveProvider] which `MainActivity` installs at the root.
 */
val LocalAppWindowAdaptive = compositionLocalOf { AppWindowAdaptive.Compact }

/**
 * Wraps [content] in a provider that recomputes the [AppWindowAdaptive]
 * whenever the underlying window adaptive info changes (size, fold posture,
 * DeX, PiP). The recomposition is driven entirely by Compose's own state
 * tracking — Material 3 adaptive subscribes to `WindowInfoTracker` under
 * the hood, no extra plumbing needed here.
 *
 * Mount once at the top of `setContent { … }` — all descendants read via
 * [LocalAppWindowAdaptive].
 */
@Composable
fun AppWindowAdaptiveProvider(content: @Composable () -> Unit) {
    val m3Info = currentWindowAdaptiveInfo()
    val configuration = LocalConfiguration.current
    val activity = LocalContext.current.findActivity()

    // DeX / desktop launcher / Chromebook desktop — Configuration reports
    // UI_MODE_TYPE_DESK. Recomposes automatically because Compose tracks
    // LocalConfiguration's identity.
    val isInDex = remember(configuration.uiMode) {
        (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_DESK
    }

    // PiP changes the Activity flag — but we only re-read on configuration
    // change so we get one false-negative window when entering PiP without
    // a config change. Phase 9 (real PiP support) will fix this by
    // hoisting a Flow<Boolean> from MainActivity instead.
    val isInPip = remember(configuration) {
        activity?.isInPictureInPictureMode == true
    }

    // Material 3 adaptive (1.1.x) exposes posture via `windowPosture.isTabletop`.
    // BOOK mode (vertical hinge) doesn't have a dedicated bool — we'll wire
    // it from `androidx.window.WindowInfoTracker` directly in Phase 4 when
    // we actually split the chat around a vertical hinge. For now: tabletop
    // OR flat. Anything not-tabletop renders as flat — a Z Fold opened to
    // an L shape in book posture just shows the regular layout, which is
    // safe (not wrong, just not optimized).
    val posture = if (m3Info.windowPosture.isTabletop) FoldPosture.TABLETOP
        else FoldPosture.FLAT

    val adaptive = AppWindowAdaptive(
        windowSizeClass = m3Info.windowSizeClass,
        posture = posture,
        isInDex = isInDex,
        isInPip = isInPip,
    )

    androidx.compose.runtime.CompositionLocalProvider(
        LocalAppWindowAdaptive provides adaptive,
        content = content,
    )
}

/**
 * Walk a Compose `LocalContext` up its `ContextWrapper` chain looking for
 * an Activity. `LocalContext.current` is a `ContextThemeWrapper` inside a
 * Composable, so directly casting to Activity fails — the cast has to
 * traverse the wrapping chain Compose installs.
 */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
