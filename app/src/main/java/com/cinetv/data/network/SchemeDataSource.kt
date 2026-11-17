package com.cinetv.data.network

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener

/**
 * Aiguillage du lecteur : un fichier « smb:// » passe par le partage réseau,
 * tout le reste (disque USB, sous-titre mis en cache) par la voie habituelle.
 * Nécessaire dès qu'un film du réseau est accompagné d'un sous-titre local.
 */
@OptIn(UnstableApi::class)
class SchemeDataSource(
    private val network: DataSource,
    private val local: DataSource
) : DataSource {

    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        network.addTransferListener(transferListener)
        local.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val chosen = if (dataSpec.uri.scheme == "smb") network else local
        active = chosen
        return chosen.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: -1

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }

    class Factory(private val context: Context) : DataSource.Factory {
        private val shares = NetworkShares(context)
        override fun createDataSource(): DataSource =
            SchemeDataSource(SmbDataSource(shares), DefaultDataSource(context, /* allowCrossProtocolRedirects = */ true))
    }
}
