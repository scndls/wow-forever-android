package app.gamenative.ui.screen.xserver

import android.util.Log
import android.view.KeyEvent

/**
 * Controller chord for the on-screen keyboard (chat, the Battle.net authenticator code and other text
 * fields): click both sticks in together (L3 + R3).
 *
 * Home and M can't be used: on controllers like the GameSir G8+ they are firmware function keys
 * (home + D-pad is volume, M combos set turbo and mapping), so Android often never sees them. The
 * stick clicks always arrive. The first click goes to the game as usual; the second one, made while
 * the first is still held, is swallowed and toggles the keyboard instead.
 *
 * All calls come from the main thread (Android input dispatch). Logs under the "WowShortcuts" tag.
 */
object ControllerShortcuts {
    private const val TAG = "WowShortcuts"

    var onToggleKeyboard: (() -> Unit)? = null

    private var leftHeld = false
    private var rightHeld = false
    private val swallowRelease = mutableSetOf<Int>()

    /** Returns true when the event was consumed. */
    fun onKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_BUTTON_THUMBL && code != KeyEvent.KEYCODE_BUTTON_THUMBR) return false
        val isLeft = code == KeyEvent.KEYCODE_BUTTON_THUMBL
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return swallowRelease.contains(code)
                val otherHeld = if (isLeft) rightHeld else leftHeld
                if (isLeft) leftHeld = true else rightHeld = true
                if (otherHeld) {
                    swallowRelease += code
                    Log.i(TAG, "L3 + R3: toggle keyboard (handler=${onToggleKeyboard != null})")
                    onToggleKeyboard?.invoke()
                    true
                } else {
                    false
                }
            }
            KeyEvent.ACTION_UP -> {
                if (isLeft) leftHeld = false else rightHeld = false
                swallowRelease.remove(code)
            }
            else -> false
        }
    }

    fun reset() {
        onToggleKeyboard = null
        leftHeld = false
        rightHeld = false
        swallowRelease.clear()
    }
}
