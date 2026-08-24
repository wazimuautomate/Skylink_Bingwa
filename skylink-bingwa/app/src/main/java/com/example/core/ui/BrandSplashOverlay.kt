package com.example.core.ui

import android.app.Activity
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.example.R

/**
 * The animated cold-start brand splash.
 *
 * The Android 12+ system splash ([androidx.core.splashscreen]) covers the window
 * only until the first frame is ready; it cannot express a sequenced brand
 * animation. This overlay takes over from it: a full-screen View added on top of
 * the Compose content, animated with [android.view.ViewPropertyAnimator], then
 * REMOVED from the view hierarchy so it costs nothing for the rest of the session.
 *
 * It is a View — not a composable — on purpose. The choreography is specified in
 * view terms (translationY in dp, an overshoot interpolator, removal from the
 * hierarchy), and running it outside Compose means the first composition, the
 * cached-catalogue read and the initial sync all proceed underneath while it
 * plays, instead of being blocked behind it (CLAUDE.md §11: never block startup).
 *
 * Timeline (total ~1660ms, all of it overlapping real work happening behind it):
 * ```
 *   0ms     logo   scale 0.72 -> 1.00, alpha 0 -> 1, 460ms, overshoot
 *   160ms   name   alpha 0 -> 1, translationY 12dp -> 0, 320ms, decelerate
 *   480ms   entrance complete
 *   1380ms  exit   logo scale -> 1.08 and the whole overlay alpha -> 1 -> 0, 280ms
 *   1660ms  removed from the view hierarchy
 * ```
 *
 * Reduced motion (design.md §11 / CLAUDE.md §6) is honoured: when the device has
 * animations switched off the overlay shows the resolved END state and is removed
 * without any animation.
 *
 * Cold start only. [play] is a no-op after the first call in a process, so a
 * configuration change or an activity re-creation never replays it.
 */
object BrandSplashOverlay {

    // Logo entrance.
    private const val LOGO_FROM_SCALE = 0.72f
    private const val LOGO_TO_SCALE = 1f
    private const val LOGO_ENTER_MS = 460L

    // App-name entrance.
    private const val NAME_ENTER_MS = 320L
    private const val NAME_START_DELAY_MS = 160L
    private const val NAME_RISE_DP = 12f

    // Hold, then exit.
    private const val ENTRANCE_END_MS = NAME_START_DELAY_MS + NAME_ENTER_MS // 480 (> LOGO_ENTER_MS)
    private const val HOLD_MS = 900L
    private const val EXIT_MS = 280L
    private const val LOGO_EXIT_SCALE = 1.08f

    /** Static dwell before removal when the device has animations disabled. */
    private const val REDUCED_MOTION_HOLD_MS = 700L

    private const val LOGO_SIZE_DP = 132
    private const val NAME_TOP_MARGIN_DP = 20
    private const val NAME_SIDE_PADDING_DP = 24
    private const val NAME_TEXT_SP = 24f

    /** Process-wide guard: the splash belongs to a cold start, never to a re-creation. */
    @Volatile
    private var played = false

    /**
     * Plays the splash once per process.
     *
     * @param activity the host; the overlay is added to its `android.R.id.content`.
     * @param reducedMotion true when the device's animator duration scale is 0 —
     *   resolve straight to the end state instead of animating. Pass the same value
     *   the app already derives in `MainActivity`; this object never reads settings
     *   itself so it stays trivially testable.
     */
    fun play(activity: Activity, reducedMotion: Boolean) {
        if (played) return
        played = true
        if (activity.isFinishing) return

        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val density = activity.resources.displayMetrics.density

        val root = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(ContextCompat.getColor(activity, R.color.splash_background))
            // Swallow taps so a stray touch cannot reach the UI mounting behind it.
            isClickable = true
            isFocusable = true
        }

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val logoPx = (LOGO_SIZE_DP * density).toInt()
        val logo = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(logoPx, logoPx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.ic_splash_logo)
            // Decorative: the app name below carries the accessible label.
            contentDescription = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val sidePaddingPx = (NAME_SIDE_PADDING_DP * density).toInt()
        val name = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (NAME_TOP_MARGIN_DP * density).toInt()
            }
            setPadding(sidePaddingPx, 0, sidePaddingPx, 0)
            text = activity.getString(R.string.app_name)
            gravity = Gravity.CENTER
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, NAME_TEXT_SP)
            setTextColor(ContextCompat.getColor(activity, R.color.splash_on_background))
            // Brand typeface; a missing/failed font must never crash the splash.
            runCatching { ResourcesCompat.getFont(activity, R.font.poppins_semibold) }
                .getOrNull()
                ?.let { typeface = it }
        }

        column.addView(logo)
        column.addView(name)
        root.addView(column)
        // Added last so it sits above the Compose content view.
        content.addView(root)

        if (reducedMotion) {
            // End state, no motion, short dwell so the brand still registers.
            root.postDelayed({ remove(root) }, REDUCED_MOTION_HOLD_MS)
            return
        }

        // --- Entrance -------------------------------------------------------
        logo.scaleX = LOGO_FROM_SCALE
        logo.scaleY = LOGO_FROM_SCALE
        logo.alpha = 0f
        logo.animate()
            .scaleX(LOGO_TO_SCALE)
            .scaleY(LOGO_TO_SCALE)
            .alpha(1f)
            .setDuration(LOGO_ENTER_MS)
            .setInterpolator(OvershootInterpolator())
            .start()

        name.alpha = 0f
        name.translationY = NAME_RISE_DP * density
        name.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(NAME_ENTER_MS)
            .setStartDelay(NAME_START_DELAY_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // --- Hold, then exit ------------------------------------------------
        root.postDelayed({
            if (root.parent == null) return@postDelayed
            logo.animate()
                .scaleX(LOGO_EXIT_SCALE)
                .scaleY(LOGO_EXIT_SCALE)
                .setDuration(EXIT_MS)
                .setInterpolator(AccelerateInterpolator())
                .start()
            root.animate()
                .alpha(0f)
                .setDuration(EXIT_MS)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { remove(root) }
                .start()
        }, ENTRANCE_END_MS + HOLD_MS)
    }

    /**
     * Detaches the overlay. Safe to call more than once and safe when the host
     * window is already gone — a splash must never be the thing that crashes a
     * cold start.
     */
    private fun remove(root: View) {
        runCatching {
            root.animate().cancel()
            (root.parent as? ViewGroup)?.removeView(root)
        }
    }
}
