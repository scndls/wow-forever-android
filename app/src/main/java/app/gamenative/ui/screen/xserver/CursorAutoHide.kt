package app.gamenative.ui.screen.xserver

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Hides the mouse cursor the X server draws while the player uses a controller, the way
 * gamescope does on the Steam Deck.
 *
 * Games such as WoW keep their last cursor image set in controller mode and never hide it,
 * so without this the pointer sits frozen on screen. Touch or mouse input shows it again;
 * it hides on the next controller input, or after [IDLE_HIDE_MS] without pointer input.
 *
 * All calls come from the main thread (Android input dispatch).
 */
object CursorAutoHide {
    private const val IDLE_HIDE_MS = 3000L
    private const val STICK_THRESHOLD = 0.3f
    private val CONTROLLER_AXES = intArrayOf(
        MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
        MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
        MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val idleHide = Runnable { setHidden(true) }
    private var onChange: (() -> Unit)? = null

    var hidden = false
        private set

    /** [apply] re-applies the cursor visibility; it runs whenever [hidden] changes. */
    fun attach(apply: () -> Unit) {
        onChange = apply
        hidden = false
        handler.removeCallbacks(idleHide)
        handler.postDelayed(idleHide, IDLE_HIDE_MS)
    }

    fun detach() {
        handler.removeCallbacks(idleHide)
        onChange = null
        hidden = false
    }

    fun onControllerKey(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN) onControllerInput()
    }

    /** Ignores stick drift: only a deliberate push past [STICK_THRESHOLD] counts. */
    fun onControllerMotion(event: MotionEvent) {
        if (CONTROLLER_AXES.any { abs(event.getAxisValue(it)) > STICK_THRESHOLD }) onControllerInput()
    }

    fun onPointerInput() {
        handler.removeCallbacks(idleHide)
        handler.postDelayed(idleHide, IDLE_HIDE_MS)
        setHidden(false)
    }

    private fun onControllerInput() {
        handler.removeCallbacks(idleHide)
        setHidden(true)
    }

    private fun setHidden(value: Boolean) {
        if (hidden == value) return
        hidden = value
        onChange?.invoke()
    }
}
