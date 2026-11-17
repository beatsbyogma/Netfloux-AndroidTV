package com.cinetv.ui.player

import android.content.Context
import android.os.Handler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.ui.CaptionStyleCompat
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.cinetv.ui.components.entrance
import com.cinetv.ui.theme.Ink
import com.cinetv.ui.theme.Motion
import com.cinetv.ui.theme.Radius
import kotlinx.coroutines.delay
import java.util.ArrayList

// ───────────────────────────────────────────────── enchaînements sans réponse

/**
 * Combien d'épisodes se sont enchaînés tout seuls, sans une seule touche.
 * Au-delà de [limit], le lecteur demande si quelqu'un regarde encore.
 */
object WatchStreak {
    const val limit = 3
    var count = 0
}

// ──────────────────────────────────────────────────── décalage image / son

/**
 * Le lecteur avec une image que l'on peut avancer ou retarder par rapport au
 * son. À zéro, rien ne change par rapport au lecteur ordinaire.
 */
@UnstableApi
class SyncRenderers(
    context: Context,
    /**
     * Vrai : le son encodé (Dolby, DTS) part tel quel vers l'ampli ou la barre de son.
     * Faux : la TV le décode elle-même. C'est le réglage par défaut — en envoi direct, le
     * lecteur ne connaît pas le retard de l'ampli, et le son se décale de l'image.
     */
    private val directAudio: Boolean,
    private val offsetUs: () -> Long
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink? {
        val sink = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        if (directAudio || sink == null) return sink
        // On répond « non » à tout son encodé : le lecteur passe alors par un décodeur de la TV
        return object : ForwardingAudioSink(sink) {
            override fun supportsFormat(format: Format): Boolean =
                format.sampleMimeType == MimeTypes.AUDIO_RAW && super.supportsFormat(format)

            override fun getFormatSupport(format: Format): Int =
                if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format)
                else AudioSink.SINK_FORMAT_UNSUPPORTED
        }
    }

    @Suppress("DEPRECATION")
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        out.add(
            object : MediaCodecVideoRenderer(
                context, mediaCodecSelector, allowedVideoJoiningTimeMs,
                enableDecoderFallback, eventHandler, eventListener, 50
            ) {
                override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
                    super.render(positionUs + offsetUs(), elapsedRealtimeUs)
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────── style des sous-titres

internal val SUB_SIZES = listOf("Petite" to 0.75f, "Normale" to 1f, "Grande" to 1.3f, "Très grande" to 1.65f)
internal val SUB_COLORS = listOf(
    "Blanc" to 0xFFFFFFFF.toInt(), "Jaune" to 0xFFFFE14D.toInt(), "Lilas" to 0xFFCDB8FF.toInt()
)
internal val SUB_BACKGROUNDS = listOf("Contour", "Ombre", "Bandeau")

@UnstableApi
internal fun captionStyle(color: Int, background: Int): CaptionStyleCompat {
    val text = SUB_COLORS[color.coerceIn(0, SUB_COLORS.lastIndex)].second
    return when (background) {
        1 -> CaptionStyleCompat(
            text, 0, 0, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, 0xFF000000.toInt(), null
        )
        2 -> CaptionStyleCompat(
            text, 0xB8000000.toInt(), 0, CaptionStyleCompat.EDGE_TYPE_NONE, 0xFF000000.toInt(), null
        )
        else -> CaptionStyleCompat(
            text, 0, 0, CaptionStyleCompat.EDGE_TYPE_OUTLINE, 0xFF000000.toInt(), null
        )
    }
}

// ──────────────────────────────────────────────────────── panneau latéral

/** Le panneau qui glisse depuis la droite : pistes, réglages, épisodes. */
@Composable
internal fun SidePanel(title: String, note: String = "", content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Ink.Void.copy(alpha = 0.62f)),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            Modifier
                .entrance(fromX = Motion.panelSlide)
                .width(330.dp)
                .fillMaxHeight()
                .background(Brush.verticalGradient(listOf(Ink.Surface, Ink.Deep)))
                .padding(horizontal = 26.dp, vertical = 28.dp)
        ) {
            Text(title.uppercase(), style = MaterialTheme.typography.headlineSmall, color = Ink.TextHi)
            if (note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(note, style = MaterialTheme.typography.labelSmall, color = Ink.TextLow)
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

/** Une ligne de réglage : gauche et droite changent la valeur, OK passe à la suivante. */
@Composable
internal fun SettingRow(
    label: String,
    value: String,
    onLess: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Radius.control)
    Surface(
        onClick = onMore,
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Ink.TextHi,
            focusedContainerColor = Ink.Violet.copy(alpha = 0.30f),
            focusedContentColor = Color.White
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(1.dp, Ink.Lilac), shape = shape)
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onLess(); true }
                    Key.DirectionRight -> { onMore(); true }
                    else -> false
                }
            }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                if (focused) "◀  $value  ▶" else value,
                style = MaterialTheme.typography.labelLarge,
                color = if (focused) Color.White else Ink.Lilac
            )
        }
    }
}

@Composable
internal fun PanelHeading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Ink.TextLow,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 12.dp)
    )
}

// ───────────────────────────────────────────────────── saisons et épisodes

/** Le menu ouvert par la pastille « Saison » : les saisons en haut, leurs épisodes dessous. */
@Composable
internal fun EpisodesPanel(
    seasons: List<PlayerSeason>,
    currentId: String,
    onPick: (String) -> Unit
) {
    val playing = seasons.indexOfFirst { season -> season.episodes.any { it.id == currentId } }.coerceAtLeast(0)
    var shown by remember { mutableIntStateOf(playing) }
    val season = seasons.getOrNull(shown) ?: return
    val start = season.episodes.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val entry = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        repeat(12) {
            if (runCatching { entry.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40)
        }
    }

    SidePanel(title = "Épisodes", note = "${season.episodes.size} épisode(s) dans cette saison") {
        if (seasons.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                seasons.take(8).forEachIndexed { index, item ->
                    SeasonPill(label = "S${item.number}", selected = index == shown, onClick = { shown = index })
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        key(shown) {
            LazyColumn(
                state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 1).coerceAtLeast(0)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(season.episodes, key = { it.id }) { episode ->
                    val index = season.episodes.indexOf(episode)
                    EpisodeCard(
                        episode = episode,
                        playing = episode.id == currentId,
                        onClick = { if (episode.id != currentId) onPick(episode.id) },
                        modifier = if (index == start) Modifier.focusRequester(entry) else Modifier
                    )
                }
            }
        }
    }
}

@Composable
private fun SeasonPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Ink.Violet.copy(alpha = 0.34f) else Color.White.copy(alpha = 0.08f),
            contentColor = Ink.TextHi,
            focusedContainerColor = Color.White,
            focusedContentColor = Ink.Void
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp))
    }
}

// ──────────────────────────────────────────────────────── petites surcouches

/** La carte « épisode suivant », en bas à droite pendant le générique de fin. */
@Composable
internal fun NextCard(title: String, thumbUrl: String, secondsLeft: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .width(318.dp)
            .clip(RoundedCornerShape(Radius.panel))
            .background(Ink.Void.copy(alpha = 0.86f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(112.dp)
                .height(63.dp)
                .clip(RoundedCornerShape(Radius.tile))
                .background(Ink.SurfaceHi)
        ) {
            if (thumbUrl.isNotBlank()) {
                AsyncImage(thumbUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("SUITE DANS $secondsLeft S", style = MaterialTheme.typography.labelSmall, color = Ink.LogoRed)
            Spacer(Modifier.height(2.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text("OK  LIRE     RETOUR  IGNORER", style = MaterialTheme.typography.labelSmall, color = Ink.TextLow)
        }
    }
}

/** Le rappel « OK pour passer l'intro », en bas à droite au début d'un épisode. */
@Composable
internal fun IntroPill(modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(Radius.control))
            .background(Ink.Void.copy(alpha = 0.72f))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("OK", style = MaterialTheme.typography.labelLarge, color = Ink.LogoRed)
        Spacer(Modifier.width(8.dp))
        Text("PASSER L'INTRO", style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

/** Les informations techniques du fichier, en haut à gauche. */
@Composable
internal fun TechPanel(lines: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(Radius.panel))
            .background(Ink.Void.copy(alpha = 0.78f))
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        lines.forEach { (label, value) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.TextLow,
                    modifier = Modifier.width(74.dp)
                )
                Text(value, style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
    }
}

/** Ce que le lecteur sait du fichier en cours de lecture. */
@UnstableApi
internal fun techLines(player: ExoPlayer, sizeBytes: Long, durationMs: Long): List<Pair<String, String>> = buildList {
    player.videoFormat?.let { video ->
        if (video.width > 0 && video.height > 0) {
            val name = when {
                video.width >= 3800 || video.height >= 2100 -> "4K"
                video.width >= 1900 || video.height >= 1060 -> "1080p"
                video.width >= 1260 || video.height >= 700 -> "720p"
                else -> "SD"
            }
            add("Image" to "$name  ·  ${video.width} × ${video.height}")
        }
        val codec = when {
            video.codecs?.startsWith("dvh") == true || video.codecs?.startsWith("dva") == true -> "Dolby Vision"
            video.sampleMimeType?.contains("hevc", true) == true -> "HEVC (H.265)"
            video.sampleMimeType?.contains("avc", true) == true -> "H.264"
            video.sampleMimeType?.contains("av01", true) == true -> "AV1"
            video.sampleMimeType?.contains("vp9", true) == true -> "VP9"
            video.sampleMimeType?.contains("mp4v", true) == true -> "MPEG-4"
            else -> video.sampleMimeType?.substringAfter('/')?.uppercase().orEmpty()
        }
        val range = when (video.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> "HDR10"
            C.COLOR_TRANSFER_HLG -> "HLG"
            else -> "SDR"
        }
        val rate = video.frameRate.takeIf { it > 0f }?.let { "%.2f i/s".format(it).replace(",00", "").replace(".00", "") }
        add("Vidéo" to listOfNotNull(codec.takeIf { it.isNotBlank() }, range, rate).joinToString("  ·  "))
    }
    player.audioFormat?.let { audio ->
        val channels = when (audio.channelCount) { 1 -> "Mono"; 2 -> "Stéréo"; 6 -> "5.1"; 8 -> "7.1"; else -> null }
        val codec = audio.sampleMimeType?.substringAfter('/')?.uppercase()
        val rate = audio.sampleRate.takeIf { it > 0 }?.let { "${it / 1000} kHz" }
        add("Son" to listOfNotNull(codec, channels, rate).joinToString("  ·  "))
    }
    if (sizeBytes > 0) {
        val size = if (sizeBytes >= 1_000_000_000L) "%.1f Go".format(sizeBytes / 1e9) else "${sizeBytes / 1_000_000} Mo"
        val flow = if (durationMs > 0) "%.1f Mb/s".format(sizeBytes * 8.0 / (durationMs / 1000.0) / 1e6) else null
        add("Fichier" to listOfNotNull(size, flow).joinToString("  ·  "))
    }
}
