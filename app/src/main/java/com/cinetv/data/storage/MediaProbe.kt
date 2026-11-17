package com.cinetv.data.storage

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.cinetv.data.model.Node
import com.cinetv.data.network.NetworkShare
import com.cinetv.data.network.NetworkShares
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Ce que contient réellement le fichier : définition, HDR, pistes audio et sous-titres. */
data class TechInfo(
    val resolution: String,          // « 4K », « 1080p »…, vide si inconnue
    val hdr: String,                 // « HDR10 », « HLG », « Dolby Vision », ou vide
    val audio: List<String>,         // « FR 5.1 », « EN stéréo »…
    val subtitles: List<String>,     // « FR », « EN »…
    /** Définition rangée en 2160, 1080, 720, 480 ou 360 ; 0 si la vidéo n'a pas livré sa taille. */
    val quality: Int = 0
) {
    val isEmpty: Boolean get() = resolution.isBlank() && hdr.isBlank() && audio.isEmpty() && subtitles.isEmpty()
}

/**
 * Ouvre l'en-tête d'un fichier vidéo pour lire ses pistes, sans rien décoder.
 * Les résultats sont gardés en mémoire le temps de la session.
 */
object MediaProbe {

    private val cache = ConcurrentHashMap<String, TechInfo>()

    fun probe(context: Context, shares: NetworkShares, node: Node): TechInfo? {
        cache[node.id]?.let { return it }
        if (node.isDirectory || node.documentUri.isBlank() || !node.isAvailable) return null

        val extractor = MediaExtractor()
        var remote: SmbSource? = null
        return try {
            if (node.documentUri.startsWith("smb://")) {
                val share = shares.find(node.volumeId) ?: return null
                remote = SmbSource(share, node.relativePath)
                extractor.setDataSource(remote)
            } else {
                extractor.setDataSource(context, Uri.parse(node.documentUri), null)
            }
            read(extractor).also { cache[node.id] = it }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
            runCatching { remote?.close() }
        }
    }

    private fun read(extractor: MediaExtractor): TechInfo {
        var resolution = ""
        var hdr = ""
        var quality = 0
        val audio = LinkedHashSet<String>()
        val subtitles = LinkedHashSet<String>()

        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            when {
                mime.startsWith("video/") && resolution.isBlank() -> {
                    val width = format.intOr(MediaFormat.KEY_WIDTH)
                    val height = format.intOr(MediaFormat.KEY_HEIGHT)
                    quality = com.cinetv.data.model.qualityOf(width, height)
                    resolution = when {
                        width >= 3200 || height >= 1700 -> "4K"
                        width >= 1800 || height >= 1000 -> "1080p"
                        width >= 1200 || height >= 700 -> "720p"
                        width > 0 -> "SD"
                        else -> ""
                    }
                    hdr = when {
                        mime.contains("dolby-vision") -> "Dolby Vision"
                        Build.VERSION.SDK_INT >= 24 &&
                            format.intOr(MediaFormat.KEY_COLOR_TRANSFER) == MediaFormat.COLOR_TRANSFER_ST2084 -> "HDR10"
                        Build.VERSION.SDK_INT >= 24 &&
                            format.intOr(MediaFormat.KEY_COLOR_TRANSFER) == MediaFormat.COLOR_TRANSFER_HLG -> "HLG"
                        else -> ""
                    }
                }
                mime.startsWith("audio/") -> {
                    val channels = when (format.intOr(MediaFormat.KEY_CHANNEL_COUNT)) {
                        1 -> "mono"; 2 -> "stéréo"; 6 -> "5.1"; 8 -> "7.1"; else -> ""
                    }
                    audio += listOf(languageOf(format), channels).filter { it.isNotBlank() }.joinToString(" ")
                        .ifBlank { "Audio" }
                }
                mime.startsWith("text/") || mime.startsWith("application/") -> {
                    languageOf(format).takeIf { it.isNotBlank() }?.let { subtitles += it }
                }
            }
        }
        return TechInfo(resolution, hdr, audio.toList(), subtitles.toList(), quality)
    }

    private fun MediaFormat.intOr(key: String, fallback: Int = 0): Int =
        runCatching { if (containsKey(key)) getInteger(key) else fallback }.getOrDefault(fallback)

    /** « fre », « fra », « fr » → « FR ». */
    private fun languageOf(format: MediaFormat): String {
        val raw = runCatching { format.getString(MediaFormat.KEY_LANGUAGE) }.getOrNull().orEmpty()
        if (raw.isBlank() || raw == "und" || raw == "```") return ""
        val two = runCatching {
            Locale.getAvailableLocales().firstOrNull {
                it.language.isNotEmpty() && (it.language == raw || runCatching { it.isO3Language }.getOrNull() == raw)
            }?.language
        }.getOrNull()
        val code = when (raw) { "fre" -> "fr"; "ger" -> "de"; "dut" -> "nl"; "chi" -> "zh"; else -> two ?: raw }
        return code.take(3).uppercase()
    }

    /** Laisse le lecteur d'en-têtes d'Android piocher dans un fichier du partage. */
    private class SmbSource(share: NetworkShare, relativePath: String) : MediaDataSource() {
        private val file: SmbFile = NetworkShares.open(share, relativePath)
        private val access = SmbRandomAccessFile(file, "r")
        private val length = access.length()

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= length) return -1
            access.seek(position)
            val read = access.read(buffer, offset, size)
            return if (read <= 0) -1 else read
        }

        override fun getSize(): Long = length

        override fun close() {
            runCatching { access.close() }
            runCatching { file.close() }
        }
    }
}
