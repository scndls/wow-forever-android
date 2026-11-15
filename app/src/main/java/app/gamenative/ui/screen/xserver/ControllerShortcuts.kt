package app.gamenative.ui.screen.xserver

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Steam Deck style chords on the controller's home button: hold home and press D-pad up to toggle the
 * on-screen keyboard (for chat, the Battle.net authenticator code and other text fields).
 *
 * Home is held back until it is released. Pressed on its own it is then replayed, so it still does
 * whatever it did before (menu binding or passthrough to the game); used in a chord it is swallowed.
 * The D-pad arrives either as key events or as hat-axis motion depending on the controller, so both
 * are handled. The GameSir "M" button never reaches Android, so home is the only usable modifier.
 *
 * All calls come from the main thread (Android input dispatch). Logs under the "WowShortcuts" tag.
 */
object ControllerShortcuts {
    private const val TAG = "WowShortcuts"
    private const val HAT_PRESSED = 0.5f

    var onToggleKeyboard: (() -> Unit)? = null

    private var heldHome: KeyEvent? = null
    private var chordUsed = false
    private var swallowUpKeyRelease = false
    private var hatUpLatched = false
    private var replaying = false

    /**
     * Returns true when the event was consumed. [replay] re-dispatches a held-back event through
     * the normal input path; events arriving during a replay pass straight through.
     */
    fun onKey(event: KeyEvent, replay: (KeyEvent) -> Unit): Boolean {
        if (replaying) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_MODE -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        Log.i(TAG, "home down")
                        heldHome = event
                        chordUsed = false
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    val down = heldHome
                    heldHome = null
                    hatUpLatched = false
                    Log.i(TAG, "home up, chord=$chordUsed")
                    if (down != null && !chordUsed) {
                        replaying = true
                        try {
                            replay(down)
                            replay(event)
                        } finally {
                            replaying = false
                        }
                    }
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> when {
                heldHome != null -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        swallowUpKeyRelease = true
                        fireChord()
                    }
                    true
                }
                // D-pad up released after home: still part of the chord.
                event.action == KeyEvent.ACTION_UP && swallowUpKeyRelease -> {
                    swallowUpKeyRelease = false
                    true
                }
                else -> false
            }
            else -> false
        }
    }

    /** D-pad as a hat axis: while home is held, up toggles the keyboard and hat motion is swallowed. */
    fun onMotion(event: MotionEvent): Boolean {
        if (replaying || heldHome == null) return false
        val up = event.getAxisValue(MotionEvent.AXIS_HAT_Y) < -HAT_PRESSED
        if (up && !hatUpLatched) {
            hatUpLatched = true
            fireChord()
        } else if (!up) {
            hatUpLatched = false
        }
        return true
    }

    private fun fireChord() {
        chordUsed = true
        Log.i(TAG, "home + D-pad up: toggle keyboard (handler=${onToggleKeyboard != null})")
        onToggleKeyboard?.invoke()
    }

    fun reset() {
        onToggleKeyboard = null
        heldHome = null
        chordUsed = false
        swallowUpKeyRelease = false
        hatUpLatched = false
        replaying = false
    }
}
