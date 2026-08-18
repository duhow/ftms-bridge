package net.duhowpi.ftmsbridge.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Pads this view by the system bar and display cutout insets.
 *
 * Android 15 lays apps targeting SDK 35 or later out edge to edge, so the window extends
 * behind the status and navigation bars. Each activity here uses a plain LinearLayout with
 * the toolbar as its first child, which would otherwise be drawn underneath the status bar,
 * leaving the overflow menu overlapping the status icons and swallowing taps on it.
 */
fun View.applySystemBarInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val insets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.updatePadding(
            left = insets.left,
            top = insets.top,
            right = insets.right,
            bottom = insets.bottom
        )
        windowInsets
    }
}
