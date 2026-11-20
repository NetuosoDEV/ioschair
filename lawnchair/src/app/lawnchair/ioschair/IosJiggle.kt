/*
 * Copyright 2026, IOSChair
 *
 * Licensed under the GNU General Public License v3.0 (same as Lawnchair).
 */

package app.lawnchair.ioschair

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import com.android.launcher3.CellLayout
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.statemanager.StateManager
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.sin

/**
 * iOS "jiggle mode": while the home screen is being edited (dragging an icon or edit mode),
 * every icon and widget wobbles with its own phase, like on an iPhone.
 */
object IosJiggle {

    private const val AMPLITUDE_DEG = 1.7f
    private const val PERIOD_MS = 270L

    private val attached = WeakHashMap<Launcher, Boolean>()
    private var animator: ValueAnimator? = null
    private val touched = WeakHashMap<View, Boolean>()

    @JvmStatic
    fun attach(launcher: Launcher) {
        if (attached.containsKey(launcher)) return
        attached[launcher] = true
        launcher.stateManager.addStateListener(
            object : StateManager.StateListener<LauncherState> {
                override fun onStateTransitionStart(toState: LauncherState) = update(launcher, toState)
                override fun onStateTransitionComplete(finalState: LauncherState) = update(launcher, finalState)
            },
        )
    }

    private fun update(launcher: Launcher, state: LauncherState) {
        val on = IosPrefs.jiggle(launcher) &&
            (state == LauncherState.SPRING_LOADED || state == LauncherState.EDIT_MODE)
        if (on) start(launcher) else stop()
    }

    private fun forEachItem(launcher: Launcher, block: (View) -> Unit) {
        val workspace = launcher.workspace ?: return
        for (i in 0 until workspace.childCount) {
            val page = workspace.getChildAt(i) as? CellLayout ?: continue
            val container = page.shortcutsAndWidgets
            for (j in 0 until container.childCount) block(container.getChildAt(j))
        }
        val hotseat = launcher.hotseat ?: return
        for (page in hotseat.pageLayouts) {
            val container = page.shortcutsAndWidgets
            for (j in 0 until container.childCount) block(container.getChildAt(j))
        }
    }

    private fun start(launcher: Launcher) {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
            duration = PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                forEachItem(launcher) { v ->
                    val seed = System.identityHashCode(v)
                    val phase = (seed % 628) / 100f
                    val amp = AMPLITUDE_DEG * (if (v.width > v.height * 1.5f) 0.35f else 1f)
                    v.rotation = sin(t + phase) * amp
                    touched[v] = true
                }
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
        for (v in touched.keys.toList()) v.rotation = 0f
        touched.clear()
    }
}
