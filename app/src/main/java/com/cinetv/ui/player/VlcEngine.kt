package com.cinetv.ui.player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.cinetv.data.network.NetworkShares
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

/**
 * Le moteur de lecture : celui de VLC, embarqué dans l'application.
 *
 * Il ouvre le fichier lui-même (disque USB, partage réseau), décode le son quel que
 * soit son format (DTS, TrueHD, AC3…) et dessine les sous-titres dans l'image.
 * L'habillage du lecteur ne lui parle que par cette classe : lecture, pause,
 * déplacement, pistes, décalages, cadrage.
 *
 * Réglages volontairement sages : décodage matériel quand la TV sait faire, logiciel
 * sinon ; une réserve de lecture de quelques secondes ; rien d'autre.
 */
class VlcEngine(
    private val context: Context,
    private val uri: String,
    /** Apparence des sous-titres : rangs dans SUB_SIZES, SUB_COLORS, SUB_BACKGROUNDS. */
    subtitleSize: Int,
    subtitleColor: Int,
    subtitleBackground: Int,
    private val preferredAudio: String,
    private val preferredSubtitle: String,
    /** Faux : aucun sous-titre tant que l'utilisateur n'en choisit pas un. */
    private var wantSubtitles: Boolean
) {
    /** Ce que le moteur signale à l'écran ; toujours appelé sur le fil principal. */
    interface Listener {
        fun onBuffering(buffering: Boolean)
        fun onPlaying(playing: Boolean)
        fun onLength(durationMs: Long)
        fun onTracks()
        fun onEnded()
        fun onError()
        /** Le chemin pris par le son de la piste en cours : direct vers l'ampli, ou décodé par l'application. */
        fun onAudioRoute(direct: Boolean)
    }

    var listener: Listener? = null

    private val libVlc = LibVLC(
        context,
        arrayListOf(
            // Réserve de lecture : un disque qui se réveille ou un passage chargé ne coupe pas le film
            "--file-caching=$FILE_CACHE_MS",
            "--network-caching=$NETWORK_CACHE_MS",
            // Le son reste calé quand on change de vitesse ou que l'horloge se recale
            "--audio-time-stretch",
            // Sous-titres : taille (plus le nombre est petit, plus le texte est grand), couleur, fond
            "--freetype-rel-fontsize=${FONT_SIZES.getOrElse(subtitleSize) { 16 }}",
            "--freetype-color=${SUB_COLORS[subtitleColor.coerceIn(0, SUB_COLORS.lastIndex)].second and 0xFFFFFF}",
            "--freetype-outline-thickness=${if (subtitleBackground == 0) 4 else 0}",
            "--freetype-shadow-opacity=${if (subtitleBackground == 1) 200 else 0}",
            "--freetype-background-opacity=${if (subtitleBackground == 2) 184 else 0}",
            "--freetype-background-color=0"
        )
    )
    private val player = MediaPlayer(libVlc)

    private var started = false
    /** Vrai dès que le fichier a été ouvert : avant, un déplacement fixe seulement le point de départ. */
    val hasStarted: Boolean get() = started
    private var released = false
    private var pendingStartMs = 0L
    private var slavesAdded = false
    private var attached: VLCVideoLayout? = null
    /** Sous-titres à côté du fichier, ajoutés une fois la lecture lancée. */
    private val extraSubtitles = ArrayList<File>()

    init {
        player.setEventListener { event ->
            if (released) return@setEventListener
            when (event.type) {
                MediaPlayer.Event.Buffering -> listener?.onBuffering(event.buffering < 100f)
                MediaPlayer.Event.Playing -> {
                    listener?.onBuffering(false)
                    listener?.onPlaying(true)
                    addSlavesOnce()
                    refreshTracks()
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> listener?.onPlaying(false)
                MediaPlayer.Event.LengthChanged -> listener?.onLength(event.lengthChanged.coerceAtLeast(0L))
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> refreshTracks()
                MediaPlayer.Event.EndReached -> { listener?.onPlaying(false); listener?.onEnded() }
                MediaPlayer.Event.EncounteredError -> {
                    Log.w(TAG, "NFX VLC : lecture impossible de $uri")
                    listener?.onBuffering(false)
                    listener?.onError()
                }
            }
        }
    }

    // ------------------------------------------------------------------ image

    fun attach(layout: VLCVideoLayout) {
        if (released || attached === layout) return
        runCatching { if (attached != null) player.detachViews() }
        runCatching { player.attachViews(layout, null, false, false) }
            .onFailure { Log.w(TAG, "NFX VLC : image non rattachée : $it") }
        attached = layout
    }

    /** Cadrage : 0 = image entière, 1 = zoom (remplit l'écran en rognant), 2 = étirée. */
    fun setScale(index: Int) {
        runCatching {
            player.setVideoScale(
                when (index) {
                    1 -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                    2 -> MediaPlayer.ScaleType.SURFACE_FILL
                    else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
                }
            )
        }
    }

    // ---------------------------------------------------------------- lecture

    val isPlaying: Boolean get() = !released && runCatching { player.isPlaying }.getOrDefault(false)

    val positionMs: Long
        get() = when {
            !started || released -> pendingStartMs
            // Un déplacement attend son tour, ou vient de partir : c'est sa cible qui fait foi,
            // le moteur annonçant encore l'ancienne position pendant un instant
            queuedSeek >= 0 -> queuedSeek
            lastSeekTarget >= 0 && android.os.SystemClock.uptimeMillis() - lastSeekAt < 900 -> lastSeekTarget
            else -> runCatching { player.getTime() }.getOrDefault(0L).coerceAtLeast(0L)
        }

    val durationMs: Long
        get() = if (released) 0L else runCatching { player.getLength() }.getOrDefault(0L).coerceAtLeast(0L)

    fun play() {
        if (released) return
        if (!started) { start(); return }
        runCatching { player.play() }
    }

    fun pause() {
        if (released || !started) return
        runCatching { if (player.isPlaying) player.pause() }
    }

    private val mainThread = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastSeekAt = 0L
    private var lastSeekTarget = -1L
    private var queuedSeek = -1L
    private var seekPosted = false

    /**
     * Déplacement dans le film. Les demandes trop rapprochées (touche maintenue, sauts en rafale)
     * sont regroupées : le décodeur de la TV ne reçoit qu'un déplacement à la fois, espacés
     * d'au moins [SEEK_GAP_MS], et c'est la dernière position demandée qui l'emporte.
     * Une rafale de déplacements envoyée telle quelle peut faire tomber le décodeur matériel.
     */
    fun seekTo(ms: Long) {
        if (released) return
        val target = ms.coerceAtLeast(0L)
        if (!started) { pendingStartMs = target; return }
        val wait = SEEK_GAP_MS - (android.os.SystemClock.uptimeMillis() - lastSeekAt)
        if (wait <= 0 && !seekPosted) { seekNow(target); return }
        queuedSeek = target
        if (!seekPosted) {
            seekPosted = true
            mainThread.postDelayed({
                seekPosted = false
                val pending = queuedSeek
                queuedSeek = -1L
                if (pending >= 0 && !released) seekNow(pending)
            }, wait.coerceAtLeast(40L))
        }
    }

    private fun seekNow(target: Long) {
        lastSeekAt = android.os.SystemClock.uptimeMillis()
        lastSeekTarget = target
        runCatching { player.setTime(target) }
    }

    /** Ouvre le fichier et lance la lecture, à l'endroit demandé avant le départ. */
    private fun start() {
        started = true
        val media = runCatching { openMedia() }.getOrNull()
        if (media == null) { listener?.onError(); return }
        // Décodage par la puce de la TV quand elle sait faire ; sinon VLC décode lui-même
        media.setHWDecoderEnabled(true, false)
        if (pendingStartMs > 1_000) media.addOption(":start-time=${pendingStartMs / 1000}")
        languages(preferredAudio)?.let { media.addOption(":audio-language=$it") }
        if (wantSubtitles) languages(preferredSubtitle)?.let { media.addOption(":sub-language=$it") }
        player.setMedia(media)
        media.release()
        listener?.onBuffering(true)
        player.play()
    }

    private fun openMedia(): Media {
        val parsed = Uri.parse(uri)
        return when (parsed.scheme) {
            "smb" -> Media(libVlc, parsed).also { media ->
                // Identifiants du partage, pris dans les partages enregistrés de l'application
                val share = NetworkShares(context).all().firstOrNull {
                    it.host.equals(parsed.host, ignoreCase = true) &&
                        parsed.path.orEmpty().trimStart('/').startsWith(it.share, ignoreCase = true)
                }
                if (share != null && share.user.isNotBlank()) {
                    val domain = share.user.substringBefore('\\', "")
                    media.addOption(":smb-user=${share.user.substringAfter('\\')}")
                    media.addOption(":smb-pwd=${share.password}")
                    if (domain.isNotBlank()) media.addOption(":smb-domain=$domain")
                }
            }
            "content" -> {
                val descriptor = context.contentResolver.openFileDescriptor(parsed, "r")
                    ?: throw IllegalStateException("fichier inaccessible")
                Media(libVlc, descriptor.fileDescriptor)
            }
            "file" -> Media(libVlc, parsed.path.orEmpty())
            null, "" -> Media(libVlc, uri)
            else -> Media(libVlc, parsed)
        }
    }

    /** « fr » → « fr,fre,fra » : VLC reconnaît une piste sous l'une ou l'autre de ces formes. */
    private fun languages(code: String): String? {
        val short = code.trim().lowercase().take(2)
        if (short.length < 2) return null
        val long = when (short) {
            "fr" -> listOf("fre", "fra"); "en" -> listOf("eng"); "es" -> listOf("spa"); "de" -> listOf("ger", "deu")
            "it" -> listOf("ita"); "ja" -> listOf("jpn"); "pt" -> listOf("por"); else -> emptyList()
        }
        return (listOf(short) + long + code.trim().lowercase()).distinct().joinToString(",")
    }

    // ----------------------------------------------------------------- pistes

    private fun refreshTracks() {
        // Sous-titres coupés par l'utilisateur : VLC ne doit pas en choisir un de lui-même
        if (!wantSubtitles) runCatching { if (player.spuTrack != -1) player.setSpuTrack(-1) }
        applyAudioRoute()
        listener?.onTracks()
    }

    private fun options(tracks: Array<MediaPlayer.TrackDescription>?, selectedId: Int, fallback: String): List<TrackOption> =
        tracks.orEmpty().filter { it.id != -1 }.mapIndexed { index, track ->
            TrackOption(
                groupIndex = track.id,
                trackIndex = 0,
                label = track.name?.takeIf { it.isNotBlank() } ?: "$fallback ${index + 1}",
                language = languageOf(track.id),
                selected = track.id == selectedId
            )
        }

    /** La langue d'une piste, telle que le fichier la déclare (« fr », « eng »…), ou vide. */
    private fun languageOf(trackId: Int): String = runCatching {
        val media = player.media ?: return@runCatching ""
        try {
            (0 until media.trackCount).firstNotNullOfOrNull { i ->
                media.getTrack(i)?.takeIf { it.id == trackId }?.language
            }.orEmpty()
        } finally {
            media.release()
        }
    }.getOrDefault("")

    fun audioOptions(): List<TrackOption> =
        if (released) emptyList() else runCatching { options(player.audioTracks, player.audioTrack, "Piste") }.getOrDefault(emptyList())

    fun subtitleOptions(): List<TrackOption> =
        if (released) emptyList() else runCatching { options(player.spuTracks, player.spuTrack, "Sous-titre") }.getOrDefault(emptyList())

    fun pickAudio(option: TrackOption) {
        runCatching { player.setAudioTrack(option.groupIndex) }
        // L'autre piste n'a peut-être pas le même format : son chemin est revu quand VLC l'a prise
    }

    fun pickSubtitle(option: TrackOption) {
        wantSubtitles = true
        runCatching { player.setSpuTrack(option.groupIndex) }
    }

    fun disableSubtitles() {
        wantSubtitles = false
        runCatching { player.setSpuTrack(-1) }
    }

    /** Sous-titres rangés à côté du fichier : proposés dans la liste, sans s'afficher d'office. */
    fun offerSubtitles(files: List<File>) { extraSubtitles += files }

    private fun addSlavesOnce() {
        if (slavesAdded) return
        slavesAdded = true
        extraSubtitles.forEach { file ->
            runCatching { player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(file), false) }
        }
    }

    /** Un sous-titre téléchargé pendant la lecture : ajouté et affiché tout de suite. */
    fun showSubtitle(file: File) {
        wantSubtitles = true
        runCatching { player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(file), true) }
    }

    // -------------------------------------------------------------- décalages

    /** Positif : les sous-titres arrivent plus tard. */
    fun setSubtitleDelayMs(ms: Long) { runCatching { player.setSpuDelay(ms * 1000) } }

    /** Positif : le son arrive plus tard. */
    fun setAudioDelayMs(ms: Long) { runCatching { player.setAudioDelay(ms * 1000) } }

    // ------------------------------------------------------------ sortie du son

    /** 0 = automatique, 1 = toujours décodé par l'application, 2 = toujours direct vers l'ampli. */
    private var audioMode = AUDIO_AUTO
    /** Le chemin en place : null tant qu'aucun n'a été choisi pour ce fichier. */
    private var routeDirect: Boolean? = null

    fun setAudioMode(mode: Int) {
        audioMode = mode
        if (started) applyAudioRoute(force = true)
    }

    /**
     * Choisit le chemin du son pour la piste en cours.
     *
     * Direct : la piste part telle quelle, l'ampli (ou la TV) la décode et garde le 5.1 —
     * possible seulement pour le Dolby (AC3, E-AC3) et le DTS.
     * Décodé : l'application décode elle-même et sort en stéréo — c'est ce que toute TV et tout
     * ampli acceptent ; un son décodé en 5.1 est refusé par beaucoup d'installations, d'où le silence.
     * En automatique, on prend le direct quand le format le permet, le décodé sinon.
     */
    private fun applyAudioRoute(force: Boolean = false) {
        if (released) return
        val codec = currentAudioCodec()
        if (codec.isEmpty() && !force) return
        val canGoDirect = DIRECT_FORMATS.any { it in codec }
        val direct = when (audioMode) {
            AUDIO_DECODED -> false
            AUDIO_DIRECT -> true
            else -> canGoDirect
        }
        if (routeDirect == direct && !force) return
        routeDirect = direct
        Log.w(TAG, "NFX VLC son : piste « $codec » → ${if (direct) "direct" else "décodé stéréo"}")
        runCatching { player.setAudioDigitalOutputEnabled(direct) }
        if (!direct) runCatching { player.setAudioOutputDevice("stereo") }
        listener?.onAudioRoute(direct)
    }

    /** Le format de la piste son en cours, en minuscules (« a52 », « eac3 », « dts », « mp4a »…), ou vide. */
    private fun currentAudioCodec(): String = runCatching {
        val media = player.media ?: return@runCatching ""
        try {
            val current = player.audioTrack
            (0 until media.trackCount).firstNotNullOfOrNull { i ->
                (media.getTrack(i) as? IMedia.AudioTrack)?.takeIf { it.id == current }
            }?.let { "${it.originalCodec.orEmpty()} ${it.codec.orEmpty()}".lowercase().trim() }.orEmpty()
        } finally {
            media.release()
        }
    }.getOrDefault("")

    // ------------------------------------------------------------ informations

    /** Ce que le moteur sait du fichier en cours : image, vidéo, son, taille. */
    fun techLines(sizeBytes: Long, durationMs: Long): List<Pair<String, String>> = buildList {
        runCatching { player.currentVideoTrack }.getOrNull()?.let { video ->
            if (video.width > 0 && video.height > 0) {
                val name = when {
                    video.width >= 3800 || video.height >= 2100 -> "4K"
                    video.width >= 1900 || video.height >= 1060 -> "1080p"
                    video.width >= 1260 || video.height >= 700 -> "720p"
                    else -> "SD"
                }
                add("Image" to "$name  ·  ${video.width} × ${video.height}")
            }
            val rate = if (video.frameRateDen > 0 && video.frameRateNum > 0) {
                "%.2f i/s".format(video.frameRateNum.toFloat() / video.frameRateDen).replace(",00", "").replace(".00", "")
            } else null
            add("Vidéo" to listOfNotNull(video.codec?.takeIf { it.isNotBlank() }, rate).joinToString("  ·  "))
        }
        runCatching {
            val media = player.media ?: return@runCatching
            try {
                val current = player.audioTrack
                (0 until media.trackCount).firstNotNullOfOrNull { i ->
                    (media.getTrack(i) as? IMedia.AudioTrack)?.takeIf { it.id == current }
                }?.let { audio ->
                    val channels = when (audio.channels) { 1 -> "Mono"; 2 -> "Stéréo"; 6 -> "5.1"; 8 -> "7.1"; else -> null }
                    val rate = audio.rate.takeIf { it > 0 }?.let { "${it / 1000} kHz" }
                    add("Son" to listOfNotNull(audio.codec?.takeIf { it.isNotBlank() }, channels, rate).joinToString("  ·  "))
                }
            } finally {
                media.release()
            }
        }
        if (sizeBytes > 0) {
            val size = if (sizeBytes >= 1_000_000_000L) "%.1f Go".format(sizeBytes / 1e9) else "${sizeBytes / 1_000_000} Mo"
            val flow = if (durationMs > 0) "%.1f Mb/s".format(sizeBytes * 8.0 / (durationMs / 1000.0) / 1e6) else null
            add("Fichier" to listOfNotNull(size, flow).joinToString("  ·  "))
        }
        add("Moteur" to "VLC")
    }

    // ------------------------------------------------------------------- fin

    fun release() {
        if (released) return
        released = true
        listener = null
        mainThread.removeCallbacksAndMessages(null)
        runCatching { player.setEventListener(null) }
        runCatching { player.stop() }
        runCatching { player.detachViews() }
        runCatching { player.release() }
        runCatching { libVlc.release() }
    }

    private companion object {
        const val TAG = "NFX"
        /** Écart minimal entre deux déplacements envoyés au décodeur, en millisecondes. */
        const val SEEK_GAP_MS = 450L
        const val AUDIO_AUTO = 0
        const val AUDIO_DECODED = 1
        const val AUDIO_DIRECT = 2
        /** Les formats qu'un ampli ou une TV sait recevoir tels quels : Dolby (AC3, E-AC3) et DTS. */
        val DIRECT_FORMATS = listOf("a52", "ac3", "ac-3", "eac3", "e-ac", "dts")
        /** Réserve de lecture, en millisecondes : fichier local (disque USB) et réseau. */
        const val FILE_CACHE_MS = 3000
        const val NETWORK_CACHE_MS = 4000
        /** Taille des sous-titres pour VLC, du plus petit au plus grand réglage (nombre plus petit = texte plus grand). */
        val FONT_SIZES = listOf(20, 16, 13, 10)
    }
}
