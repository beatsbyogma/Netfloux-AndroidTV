package com.cinetv.data.network

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException

/**
 * Fait lire au lecteur vidéo un fichier situé sur un dossier partagé.
 *
 * Le lecteur demande « donne-moi les octets à partir de telle position » ;
 * on les lui sert directement depuis le partage, ce qui permet d'avancer et
 * de reculer dans le film sans rien télécharger d'avance.
 */
@OptIn(UnstableApi::class)
class SmbDataSource(private val shares: NetworkShares) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var file: SmbFile? = null
    private var stream: SmbRandomAccessFile? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        try {
            val address = dataSpec.uri.toString()
            val share = shareOf(address) ?: throw IOException("Partage réseau inconnu : $address")
            val relative = address.removePrefix("smb://").substringAfter('/').substringAfter('/')
            val target = NetworkShares.open(share, relative)
            val access = SmbRandomAccessFile(target, "r")
            file = target
            stream = access

            val length = access.length()
            if (dataSpec.position > length) throw IOException("Position au-delà de la fin du fichier")
            access.seek(dataSpec.position)
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
            else length - dataSpec.position
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Lecture réseau impossible : ${e.message}", e)
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val wanted = minOf(length.toLong(), remaining).toInt()
        val read = try {
            stream?.read(buffer, offset, wanted) ?: -1
        } catch (e: Exception) {
            throw IOException("Connexion au partage interrompue : ${e.message}", e)
        }
        if (read <= 0) return C.RESULT_END_OF_INPUT
        remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            runCatching { stream?.close() }
            runCatching { file?.close() }
        } finally {
            stream = null
            file = null
            uri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /** Retrouve le partage (et donc ses identifiants) à partir de l'adresse du fichier. */
    private fun shareOf(address: String): NetworkShare? {
        val rest = address.removePrefix("smb://")
        val host = rest.substringBefore('/')
        val share = rest.substringAfter('/', "").substringBefore('/')
        return shares.find(NetworkShare.volumeIdOf(host, share))
    }

    class Factory(context: Context) : DataSource.Factory {
        private val shares = NetworkShares(context)
        override fun createDataSource(): DataSource = SmbDataSource(shares)
    }
}
