package com.cinetv.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.cinetv.data.network.NetworkShare
import com.cinetv.data.network.NetworkShares
import jcifs.smb.SmbRandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * L'aperçu au-dessus de la timeline : va chercher dans le fichier l'image
 * la plus proche de la position visée. Un seul appel à la fois ; si le
 * fichier ne s'y prête pas (codec, réseau trop lent), on renonce sans bruit
 * et la timeline reste simplement sans vignette.
 */
class FramePreview(private val context: Context, private val address: String) {

    private val lock = Mutex()
    private var retriever: MediaMetadataRetriever? = null
    private var broken = false
    /** Vrai si la vidéo dépasse le 1080p (ou si sa taille est inconnue) ; null tant qu'on ne l'a pas regardée. */
    private var tooLarge: Boolean? = null

    suspend fun frameAt(positionMs: Long): ImageBitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (broken) return@withLock null
            try {
                val source = retriever ?: open().also { retriever = it }
                // Au-delà du 1080p, pas d'aperçu : il ouvrirait un second décodeur à côté de celui
                // du film, et la puce de la TV n'en supporte qu'un à cette taille — au risque de
                // faire tomber le décodage, voire de redémarrer la TV.
                if (tooLarge == null) {
                    val width = source.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val height = source.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    // Taille inconnue : dans le doute, on s'abstient aussi
                    tooLarge = width <= 0 || height <= 0 || width > MAX_WIDTH || height > MAX_HEIGHT
                }
                if (tooLarge == true) {
                    broken = true
                    runCatching { source.release() }
                    retriever = null
                    return@withLock null
                }
                val timeUs = positionMs * 1000
                val raw: Bitmap? = if (Build.VERSION.SDK_INT >= 27) {
                    source.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT)
                } else {
                    source.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                        val ratio = WIDTH.toFloat() / full.width.coerceAtLeast(1)
                        Bitmap.createScaledBitmap(full, WIDTH, (full.height * ratio).toInt().coerceAtLeast(1), true)
                    }
                }
                raw?.asImageBitmap()
            } catch (e: Throwable) {
                broken = true
                null
            }
        }
    }

    /** À appeler en quittant le lecteur. */
    fun release() {
        CoroutineScope(Dispatchers.IO).launch {
            lock.withLock {
                broken = true
                runCatching { retriever?.release() }
                retriever = null
            }
        }
    }

    private fun open(): MediaMetadataRetriever {
        val uri = Uri.parse(address)
        val reader = MediaMetadataRetriever()
        when (uri.scheme) {
            "smb" -> {
                val rest = address.removePrefix("smb://")
                val host = rest.substringBefore('/')
                val shareName = rest.substringAfter('/', "").substringBefore('/')
                val share = NetworkShares(context).find(NetworkShare.volumeIdOf(host, shareName))
                    ?: error("Partage inconnu")
                val relative = rest.substringAfter('/').substringAfter('/')
                reader.setDataSource(SmbMedia(SmbRandomAccessFile(NetworkShares.open(share, relative), "r")))
            }
            null, "file" -> reader.setDataSource(uri.path ?: address)
            else -> reader.setDataSource(context, uri)
        }
        return reader
    }

    /** Lecture d'un fichier du réseau, morceau par morceau, pour le système. */
    private class SmbMedia(private val file: SmbRandomAccessFile) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            file.seek(position)
            return file.read(buffer, offset, size)
        }
        override fun getSize(): Long = file.length()
        override fun close() { runCatching { file.close() } }
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 180
        /** Taille de vidéo au-delà de laquelle l'aperçu est coupé. */
        const val MAX_WIDTH = 1920
        const val MAX_HEIGHT = 1088
    }
}
