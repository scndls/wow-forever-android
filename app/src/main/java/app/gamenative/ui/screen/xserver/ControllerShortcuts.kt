package app.gamenative.ui.screen.xserver

import android.view.KeyEvent

/**
 * Steam Deck style chords on the controller's home button: hold home and press X to toggle the
 * on-screen keyboard (for chat, the Battle.net authenticator code and other text fields).
 *
 * Home is held back until it is released. Pressed on its own it is then replayed, so it still does
 * whatever it did before (menu binding or passthrough to the game); used in a chord it is swallowed.
 * The GameSir "M" button never reaches Android, so home is the only usable modifier.
 *
 * All calls come from the main thread (Android input dispatch).
 */
object ControllerShortcuts {
    var onToggleKeyboard: (() -> Unit)? = null

    private var heldHome: KeyEvent? = null
    private var chordUsed = false
    private var swallowXUp = false
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
                        heldHome = event
                        chordUsed = false
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    val down = heldHome
                    heldHome = null
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
            KeyEvent.KEYCODE_BUTTON_X -> when {
                heldHome != null -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        chordUsed = true
                        swallowXUp = true
                        onToggleKeyboard?.invoke()
                    }
                    true
                }
                // X released after home: still part of the chord.
                event.action == KeyEvent.ACTION_UP && swallowXUp -> {
                    swallowXUp = false
                    true
                }
                else -> false
            }
            else -> false
        }
    }

    fun reset() {
        onToggleKeyboard = null
        heldHome = null
        chordUsed = false
        swallowXUp = false
        replaying = false
    }
}
