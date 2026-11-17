package com.cinetv.data.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import com.cinetv.data.model.Node
import com.cinetv.data.network.NetworkShare
import com.cinetv.data.network.NetworkShares
import java.io.File

/**
 * Renommer et supprimer pour de vrai, sur le disque ou le partage.
 *
 * Chaque fonction renvoie null quand tout s'est bien passé, sinon la raison
 * de l'échec en clair. Une suppression est définitive : il n'y a pas de
 * corbeille, ni sur un disque USB ni sur un partage réseau.
 */
class FileOps(private val context: Context, private val shares: NetworkShares) {

    companion object {
        /** Caractères interdits dans un nom de fichier Windows — donc sur presque tous les disques. */
        private val FORBIDDEN = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

        const val NEEDS_ACCESS = "La TV n'autorise pas Netfloux à modifier ce disque USB"

        /**
         * Android 12 et suivants montrent les disques USB aux applications en lecture seule.
         * Quand l'écriture directe est refusée, on passe par l'index média de la TV, qui, lui,
         * a le droit d'écrire ; ce message s'affiche si ce second chemin échoue aussi.
         */
        const val READ_ONLY = "La TV présente ce disque USB en lecture seule aux applications : " +
            "ni l'accès direct ni l'index média de la TV n'ont pu le modifier"
        private const val TAG = "NFX"

        fun checkName(name: String): String? = when {
            name.isBlank() -> "Le nom ne peut pas être vide"
            name.any { it in FORBIDDEN } -> "Le nom contient un caractère interdit ( \\ / : * ? \" < > | )"
            name.endsWith(".") || name.endsWith(" ") -> "Le nom ne peut pas finir par un point ou un espace"
            else -> null
        }

        /** Sur Android récent, modifier un disque demande l'accès « tous les fichiers ». */
        fun canModifyLocal(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    private fun isNetwork(node: Node) = NetworkShare.isNetwork(node.volumeId)

    private fun localFile(node: Node): File? =
        StorageAccess.mountPointFor(node.volumeId)?.let { File(it, node.relativePath) }

    private fun smbPath(node: Node, relative: String = node.relativePath) =
        if (node.isDirectory) "$relative/" else relative

    fun delete(node: Node): String? = try {
        if (isNetwork(node)) {
            val share = shares.find(node.volumeId) ?: return "Partage réseau oublié"
            NetworkShares.open(share, smbPath(node)).use { it.delete() }
            null
        } else {
            val file = localFile(node)
            when {
                !canModifyLocal() -> NEEDS_ACCESS
                file == null || !file.exists() -> "Fichier introuvable sur le disque"
                !deleteLocal(file) -> READ_ONLY
                else -> {
                    MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null)
                    null
                }
            }
        }
    } catch (e: Exception) {
        "Suppression impossible : ${e.message ?: e.javaClass.simpleName}"
    }

    // ------------------------------------------------------------------
    // Disque USB : écriture directe d'abord, index média de la TV ensuite

    /** Supprime un fichier, ou un dossier et tout son contenu. Vrai s'il n'en reste rien. */
    private fun deleteLocal(file: File): Boolean {
        // Fichier par fichier, du plus profond au moins profond : un échec n'empêche pas d'essayer la suite
        val all = if (file.isDirectory) file.walkBottomUp().toList() else listOf(file)
        var viaIndex = 0
        for (item in all) {
            if (!item.exists() || item.delete()) continue
            if (deleteViaIndex(item)) viaIndex++
        }
        val gone = !file.exists()
        Log.w(TAG, "Suppression de ${file.path} : ${all.size} élément(s), $viaIndex par l'index média, réussie = $gone")
        return gone
    }

    private val filesIndex get() = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** La ligne de l'index média qui correspond à ce fichier ; il y est inscrit au besoin. */
    private fun indexRow(file: File): Long? {
        fun find(): Long? = runCatching {
            context.contentResolver.query(
                filesIndex, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DATA} = ? COLLATE NOCASE", arrayOf(file.path), null
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        }.getOrNull()

        find()?.let { return it }
        // Pas encore connu de l'index : on le fait inscrire, puis on regarde à nouveau
        val done = java.util.concurrent.CountDownLatch(1)
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(file.path), null) { _, _ -> done.countDown() }
            done.await(6, java.util.concurrent.TimeUnit.SECONDS)
        }
        return find()
    }

    private fun deleteViaIndex(file: File): Boolean {
        val id = indexRow(file)
        if (id == null) { Log.w(TAG, "Index média : ${file.path} introuvable"); return false }
        val outcome = runCatching {
            context.contentResolver.delete(ContentUris.withAppendedId(filesIndex, id), null, null)
        }
        outcome.exceptionOrNull()?.let { Log.w(TAG, "Index média : suppression refusée pour ${file.path} : $it") }
        return !file.exists()
    }

    /** Renomme un fichier en passant par l'index média (les dossiers ne s'y renomment pas). */
    private fun renameViaIndex(file: File, newName: String, destination: File): Boolean {
        if (file.isDirectory) return false
        val id = indexRow(file) ?: return false
        val outcome = runCatching {
            context.contentResolver.update(
                ContentUris.withAppendedId(filesIndex, id),
                ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) },
                null, null
            )
        }
        outcome.exceptionOrNull()?.let { Log.w(TAG, "Index média : renommage refusé pour ${file.path} : $it") }
        return destination.exists() && !file.exists()
    }

    /** [newName] est le nom complet, extension comprise pour un fichier. */
    fun rename(node: Node, newName: String): String? {
        checkName(newName)?.let { return it }
        val parent = node.relativePath.substringBeforeLast('/', "")
        val target = if (parent.isEmpty()) newName else "$parent/$newName"
        return try {
            if (isNetwork(node)) {
                val share = shares.find(node.volumeId) ?: return "Partage réseau oublié"
                NetworkShares.open(share, smbPath(node)).use { source ->
                    NetworkShares.open(share, smbPath(node, target)).use { destination ->
                        if (destination.exists()) return "Un élément porte déjà ce nom"
                        source.renameTo(destination)
                    }
                }
                null
            } else {
                val file = localFile(node)
                val destination = file?.parentFile?.let { File(it, newName) }
                when {
                    !canModifyLocal() -> NEEDS_ACCESS
                    file == null || destination == null || !file.exists() -> "Fichier introuvable sur le disque"
                    destination.exists() -> "Un élément porte déjà ce nom"
                    !file.renameTo(destination) && !renameViaIndex(file, newName, destination) -> READ_ONLY
                    else -> {
                        MediaScannerConnection.scanFile(context, arrayOf(file.path, destination.path), null, null)
                        null
                    }
                }
            }
        } catch (e: Exception) {
            "Renommage impossible : ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
