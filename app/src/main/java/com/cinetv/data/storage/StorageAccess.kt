package com.cinetv.data.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Accès aux disques, sans passer par le sélecteur de dossier système.
 *
 * Beaucoup d'Android TV n'embarquent pas DocumentsUI : l'intent
 * ACTION_OPEN_DOCUMENT_TREE n'y trouve aucune application. On s'appuie donc sur
 * l'index média du système, qui catalogue déjà les vidéos des disques montés,
 * avec un repli en lecture directe des points de montage.
 */
object StorageAccess {

    /** Permission à demander, selon la version d'Android. */
    val requiredPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Identifiant canonique d'un volume.
     *
     * Le même disque remonte sous plusieurs graphies selon la source :
     * l'index média le nomme en minuscules, le point de montage en majuscules.
     * Sans normalisation, il apparaissait deux fois dans la liste.
     */
    fun canonical(volumeName: String): String = volumeName.trim().lowercase()

    /**
     * Volumes connus de l'index média : "external_primary" pour le stockage
     * interne, puis un identifiant par disque amovible.
     */
    fun mediaVolumes(context: Context): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { MediaStore.getExternalVolumeNames(context).toList() }
                .getOrDefault(listOf(MediaStore.VOLUME_EXTERNAL))
        } else {
            listOf(MediaStore.VOLUME_EXTERNAL)
        }

    /** Volumes amovibles uniquement — le stockage interne de la TV ne nous intéresse pas. */
    fun removableMediaVolumes(context: Context): List<String> =
        mediaVolumes(context)
            .map(::canonical)
            .filter { it != "external_primary" && it != MediaStore.VOLUME_EXTERNAL }
            .distinct()

    /** Nom lisible d'un volume, tel que le système le présente. */
    fun describeVolume(context: Context, volumeName: String): String {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val match = sm?.storageVolumes?.firstOrNull {
            it.uuid?.equals(volumeName, ignoreCase = true) == true
        }
        val described = match?.getDescription(context)?.takeIf { it.isNotBlank() }
        return described
            ?: if (volumeName == "external_primary") "Stockage interne"
            else "Disque ${volumeName.uppercase()}"
    }

    /**
     * Espace libre et capacité d'un disque branché, en octets — ou null si
     * le système ne laisse pas lire son point de montage.
     */
    fun spaceOf(context: Context, volumeName: String): Pair<Long, Long>? {
        val id = canonical(volumeName)
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val fromSystem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sm?.storageVolumes?.firstOrNull { it.uuid?.equals(id, ignoreCase = true) == true }?.directory
        } else null
        val dir = fromSystem ?: mountPointFor(id) ?: return null
        return runCatching {
            val stat = android.os.StatFs(dir.path)
            val total = stat.totalBytes
            if (total <= 0) null else stat.availableBytes to total
        }.getOrNull()
    }

    /** Le volume répond-il encore ? */
    fun isVolumePresent(context: Context, volumeName: String): Boolean {
        val id = canonical(volumeName)
        return mediaVolumes(context).any { canonical(it) == id } || mountPointFor(id) != null
    }

    /**
     * Points de montage lisibles directement, pour le repli.
     * Les chemins varient selon les constructeurs, d'où cette liste.
     */
    fun mountPointFor(volumeName: String): File? {
        val candidates = listOf(
            "/storage/$volumeName",
            "/storage/${volumeName.uppercase()}",
            "/mnt/media_rw/$volumeName",
            "/mnt/media_rw/${volumeName.uppercase()}"
        )
        return candidates.map(::File).firstOrNull { it.isDirectory && it.canRead() }
    }

    /** Tous les points de montage amovibles détectables sans l'index média. */
    fun readableMountPoints(context: Context): List<File> {
        val found = linkedSetOf<File>()
        val seen = mutableSetOf<String>()

        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sm?.storageVolumes
                ?.filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
                ?.mapNotNull { it.directory }
                ?.filter { it.canRead() }
                ?.forEach { if (seen.add(canonical(it.name))) found += it }
        }

        for (root in listOf("/storage", "/mnt/media_rw", "/mnt/usb")) {
            val dir = File(root)
            if (!dir.isDirectory) continue
            dir.listFiles()?.forEach { child ->
                val name = child.name
                val skip = name == "emulated" || name == "self" || name == "container"
                // un même disque est monté à plusieurs endroits : on n'en garde qu'un
                if (!skip && child.isDirectory && child.canRead() && seen.add(canonical(name))) {
                    found += child
                }
            }
        }
        return found.toList()
    }
}
