package com.cinetv.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.KeyEvent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.cinetv.R

/**
 * Les sons de la télécommande.
 *
 * Android en joue un d'office, assez sec. L'application le coupe dans ses écrans et joue
 * les siens à la place : un même accord doux (la majeur septième), décliné selon le geste —
 *  - flèche gauche ou droite : l'accord, court (nav_side) ;
 *  - flèche haut ou bas : le même, renversé et plus grave (nav_vertical) ;
 *  - OK : l'accord qui monte, égrené (nav_click) ;
 *  - Retour : l'accord qui redescend (nav_back) ;
 *  - lancement d'un film : l'accord déployé sur deux octaves (nav_launch).
 * Rien pendant la lecture d'une vidéo. Les fichiers sont dans res/raw.
 */
object NavSound {
    /** Volume des sons, de 0 (muet) à 1. */
    private const val VOLUME = 0.32f
    /** Écart minimal entre deux sons de flèche, en millisecondes : une touche maintenue ne mitraille pas. */
    private const val GAP_MS = 90L

    /** Vrai pendant la lecture d'une vidéo : les touches y pilotent le film, pas la navigation. */
    @Volatile var muted = false

    private var pool: SoundPool? = null
    private var side = 0
    private var vertical = 0
    private var click = 0
    private var back = 0
    private var launch = 0
    private var lastAt = 0L

    fun init(context: Context) {
        if (pool != null) return
        val app = context.applicationContext
        pool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
            .also {
                side = it.load(app, R.raw.nav_side, 1)
                vertical = it.load(app, R.raw.nav_vertical, 1)
                click = it.load(app, R.raw.nav_click, 1)
                back = it.load(app, R.raw.nav_back, 1)
                launch = it.load(app, R.raw.nav_launch, 1)
            }
    }

    /** À appeler pour chaque touche reçue : joue le son du geste, à l'appui. */
    fun onKey(event: KeyEvent) {
        if (muted || event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> arrow(side)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> arrow(vertical)
            // Un appui long ne rejoue pas le son
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (event.repeatCount == 0) play(click)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (event.repeatCount == 0) play(back)
        }
    }

    /** Un film démarre : joué même si le lecteur vient de couper les sons de navigation. */
    fun launch() = play(launch)

    private fun arrow(sound: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAt < GAP_MS) return
        lastAt = now
        play(sound)
    }

    private fun play(sound: Int) {
        if (sound == 0) return
        runCatching { pool?.play(sound, VOLUME, VOLUME, 1, 0, 1f) }
    }
}

/**
 * À poser en tête du contenu d'une fenêtre (fiche, menu, confirmation) : y coupe le son
 * de navigation d'Android et y branche celui de l'application. Les fenêtres reçoivent leurs
 * touches à part de l'écran principal, d'où ce branchement fenêtre par fenêtre.
 */
@Composable
fun SoftNavSound() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.isSoundEffectsEnabled = false
        val window: Window? = (view.parent as? DialogWindowProvider)?.window
        val original = window?.callback
        if (window != null && original != null) {
            window.callback = object : Window.Callback by original {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    NavSound.onKey(event)
                    return original.dispatchKeyEvent(event)
                }
            }
        }
        onDispose { if (window != null && original != null) window.callback = original }
    }
}
