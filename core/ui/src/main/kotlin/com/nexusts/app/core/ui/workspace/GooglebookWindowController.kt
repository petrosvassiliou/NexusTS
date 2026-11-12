package com.nexusts.app.core.ui.workspace

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.compose.runtime.Immutable
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

@Immutable
data class ExternalDisplayDescriptor(
    val displayId: Int,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val refreshRateHz: Float,
    val isDefaultDisplay: Boolean
)

@Immutable
data class DesktopWindowPosture(
    val widthDp: Float,
    val heightDp: Float,
    val isFreeformDesktopWindow: Boolean,
    val isExpandedDesktopWidth: Boolean,
    val connectedDisplays: List<ExternalDisplayDescriptor>
)

/**
 * Native Googlebook OS & Android Desktop Multi-Window and Multi-Monitor Controller.
 * Strictly targets native Android/Googlebook OS freeform windowing & DisplayManager APIs
 * (zero legacy ChromeOS ARC++ or Crostini container dependencies).
 */
class GooglebookWindowController(
    private val appContext: Context
) {
    private val displayManager: DisplayManager =
        appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    fun queryConnectedDisplays(): List<ExternalDisplayDescriptor> {
        return displayManager.displays.map { display ->
            val mode = display.mode
            ExternalDisplayDescriptor(
                displayId = display.displayId,
                name = display.name ?: "Display ${display.displayId}",
                widthPx = mode.physicalWidth,
                heightPx = mode.physicalHeight,
                refreshRateHz = display.refreshRate,
                isDefaultDisplay = display.displayId == Display.DEFAULT_DISPLAY
            )
        }
    }

    /**
     * Reactive stream emitting live external monitor hotplug/resolution changes on Googlebook OS docks.
     */
    fun observeDisplays(): Flow<List<ExternalDisplayDescriptor>> = callbackFlow {
        trySend(queryConnectedDisplays())
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                trySend(queryConnectedDisplays())
            }

            override fun onDisplayRemoved(displayId: Int) {
                trySend(queryConnectedDisplays())
            }

            override fun onDisplayChanged(displayId: Int) {
                trySend(queryConnectedDisplays())
            }
        }
        displayManager.registerDisplayListener(listener, null)
        awaitClose {
            displayManager.unregisterDisplayListener(listener)
        }
    }

    /**
     * Computes live Googlebook OS window bounds & freeform posture for adaptive multi-pane layout.
     */
    fun computeWindowPosture(activity: Activity): DesktopWindowPosture {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity)
        val maxMetrics = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(activity)
        val density = activity.resources.displayMetrics.density.coerceAtLeast(1f)
        val widthDp = metrics.bounds.width() / density
        val heightDp = metrics.bounds.height() / density
        val isFreeform = metrics.bounds.width() < maxMetrics.bounds.width() ||
            metrics.bounds.height() < maxMetrics.bounds.height() ||
            activity.isInMultiWindowMode

        return DesktopWindowPosture(
            widthDp = widthDp,
            heightDp = heightDp,
            isFreeformDesktopWindow = isFreeform,
            isExpandedDesktopWidth = widthDp >= 960f,
            connectedDisplays = queryConnectedDisplays()
        )
    }

    /**
     * Detaches a [WorkspaceSessionTab] into an independent native Googlebook OS freeform window,
     * optionally targeting a specific external monitor ([targetDisplayId]) and cascade geometry.
     */
    fun launchDetachedSessionWindow(
        sourceActivity: Activity,
        detachedActivityClass: Class<out Activity>,
        serializedTabJson: String,
        targetDisplayId: Int? = null,
        freeformBoundsPx: Rect? = null
    ) {
        val intent = Intent(sourceActivity, detachedActivityClass).apply {
            putExtra(EXTRA_DETACHED_TAB_PAYLOAD, serializedTabJson)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
            )
        }

        val options = ActivityOptions.makeBasic()
        if (targetDisplayId != null) {
            options.launchDisplayId = targetDisplayId
        }
        if (freeformBoundsPx != null) {
            options.launchBounds = freeformBoundsPx
        } else {
            // Default cascaded freeform window bounds centered cleanly on target display
            val targetDisplay = queryConnectedDisplays()
                .firstOrNull { it.displayId == (targetDisplayId ?: Display.DEFAULT_DISPLAY) }
            if (targetDisplay != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val w = (targetDisplay.widthPx * 0.72f).toInt()
                val h = (targetDisplay.heightPx * 0.76f).toInt()
                val left = (targetDisplay.widthPx - w) / 2
                val top = (targetDisplay.heightPx - h) / 2
                options.launchBounds = Rect(left, top, left + w, top + h)
            }
        }
        sourceActivity.startActivity(intent, options.toBundle())
    }

    companion object {
        const val EXTRA_DETACHED_TAB_PAYLOAD = "com.nexusts.app.extra.DETACHED_TAB_PAYLOAD"
    }
}
