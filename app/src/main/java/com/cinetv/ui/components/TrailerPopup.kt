package com.cinetv.ui.components

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color as AndroidColor
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/** Part de l'écran occupée par la vidéo dans la fenêtre de bande-annonce. */
private const val POPUP_SIZE = 0.80f

/**
 * Fenêtre de bande-annonce : le lecteur YouTube seul, avec le son, sans ses
 * boutons. OK met en pause ou relance, gauche et droite reculent ou avancent
 * de dix secondes, Retour ferme. Elle se ferme d'elle-même à la fin.
 *
 * C'est une vraie fenêtre Android, à part de l'interface : le chemin
 * d'affichage le plus simple pour une vidéo web sur une TV.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TrailerPopup(videoKey: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val close by rememberUpdatedState(onDismiss)

    DisposableEffect(videoKey) {
        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val web = WebView(context).apply {
            setBackgroundColor(AndroidColor.BLACK)
            isFocusable = false
            isFocusableInTouchMode = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun ended() { post { dialog.dismiss() } }
                },
                "Netfloux"
            )
            val origin = "https://${context.packageName}"
            loadDataWithBaseURL(origin, popupPage(videoKey, origin), "text/html", "utf-8", null)
        }

        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * POPUP_SIZE).toInt()
        val height = width * 9 / 16
        val root = FrameLayout(context).apply {
            setBackgroundColor(0xE6000000.toInt())
            isFocusable = true
            isFocusableInTouchMode = true
            addView(web, FrameLayout.LayoutParams(width, height, Gravity.CENTER))
            addView(
                TextView(context).apply {
                    text = "OK  pause / lecture     ◀ ▶  10 secondes     Retour  fermer"
                    setTextColor(0x99FFFFFF.toInt())
                    textSize = 11f
                    letterSpacing = 0.08f
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { bottomMargin = ((metrics.heightPixels - height) / 2 - 34 * metrics.density).toInt().coerceAtLeast(8) }
            )
        }
        dialog.setContentView(root)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener keyCode != KeyEvent.KEYCODE_BACK
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { web.evaluateJavascript("toggle()", null); true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { web.evaluateJavascript("seek(-10)", null); true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { web.evaluateJavascript("seek(10)", null); true }
                KeyEvent.KEYCODE_BACK -> false          // la fenêtre se ferme d'elle-même
                else -> true
            }
        }
        dialog.setOnDismissListener { close() }
        dialog.show()
        root.requestFocus()

        onDispose {
            dialog.setOnDismissListener(null)
            runCatching { dialog.dismiss() }
            web.stopLoading()
            web.loadUrl("about:blank")
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
    }
}

private fun popupPage(videoKey: String, origin: String): String = """
<!DOCTYPE html>
<html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{width:100%;height:100%}</style>
</head><body><div id="p"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var player;
function toggle() { try { if (player.getPlayerState() == 1) player.pauseVideo(); else player.playVideo(); } catch (x) {} }
function seek(s) { try { player.seekTo(Math.max(0, player.getCurrentTime() + s), true); } catch (x) {} }
function onYouTubeIframeAPIReady() {
  player = new YT.Player('p', {
    width: '100%', height: '100%', videoId: '$videoKey',
    playerVars: { autoplay: 1, controls: 0, disablekb: 1, fs: 0, iv_load_policy: 3, modestbranding: 1,
                  rel: 0, playsinline: 1, hl: 'fr', origin: '$origin' },
    events: {
      onReady: function (e) { e.target.playVideo(); },
      onStateChange: function (e) { if (e.data == 0) Netfloux.ended(); }
    }
  });
}
</script></body></html>
""".trimIndent()
