package com.cinetv.data.storage

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.cinetv.data.model.Node
import java.io.File
import java.security.MessageDigest

/**
 * Construit l'arborescence d'un disque à partir de l'index média du système,
 * avec un repli en lecture directe du point de montage.
 *
 * L'index média ne stocke que des fichiers ; les dossiers sont reconstruits à
 * partir de leur chemin, ce qui préserve exactement le rangement de l'utilisateur.
 */
class MediaLibraryScanner(private val context: Context) {

    companion object {
        private const val TAG = "MediaScanner"
        private const val MIN_SIZE_BYTES = 20L * 1024 * 1024   // ignore les extraits
        private const val MAX_DEPTH = 12

        val VIDEO_EXTENSIONS = setOf(
            "mkv", "avi", "mp4", "m4v", "mov", "wmv", "flv",
            "ts", "m2ts", "mpg", "mpeg", "divx", "webm", "vob", "ogm", "rmvb", "asf", "3gp"
        )

        private val IGNORED_FOLDERS = setOf(
            "System Volume Information", "\$RECYCLE.BIN", "RECYCLER",
            "LOST.DIR", "Android", ".Trash-1000", "found.000"
        )

        /** Identifiant déterministe : même disque + même chemin = même fiche. */
        fun nodeId(volumeId: String, relativePath: String): String =
            MessageDigest.getInstance("SHA-1")
                .digest("$volumeId|$relativePath".toByteArray())
                .joinToString("") { "%02x".format(it) }
    }

    data class Output(
        val nodes: List<Node>,
        val videoCount: Int,
        val folderCount: Int,
        val source: String
    )

    /** Un fichier trouvé, avant construction de l'arborescence. */
    data class Found(
        val name: String,
        val folderPath: String,      // "Films/Action", "" à la racine
        val uri: String,
        val size: Long,
        val durationMs: Long
    )

    fun scan(volumeId: String, onProgress: (Int) -> Unit = {}): Output {
        // Le disque lui-même fait foi : on le lit en entier, dossier par dossier. L'index de la TV,
        // lui, est en retard pendant de longues minutes sur un disque qu'on vient de brancher —
        // s'y fier obligeait à relancer l'analyse plusieurs fois pour tout voir.
        val viaFiles = runCatching { walkMountPoint(volumeId, onProgress) }
            .onFailure { Log.w(TAG, "NFX scan $volumeId : lecture directe impossible (${it.message})") }
            .getOrDefault(emptyList())
        // L'index sert ensuite de complément : il connaît la durée des vidéos qu'il a déjà vues.
        val viaIndex = runCatching { queryMediaStore(volumeId) { } }.getOrDefault(emptyList())
        Log.w(TAG, "NFX scan $volumeId : ${viaFiles.size} vidéos lues sur le disque, ${viaIndex.size} dans l'index de la TV")

        if (viaFiles.isEmpty()) {
            // Disque illisible directement (cas rare) : on se contente de l'index
            onProgress(viaIndex.size)
            return assemble(volumeId, viaIndex, "index média")
        }
        val indexed = viaIndex.associateBy { "${it.folderPath}/${it.name}".lowercase() }
        // L'adresse retenue est celle du fichier lui-même : le lecteur le lit alors directement
        // sur le disque, sans passer par l'intermédiaire du système (plus lent, sujet aux coupures).
        val merged = viaFiles.map { file ->
            val known = indexed["${file.folderPath}/${file.name}".lowercase()]
            if (known != null && known.durationMs > 0) file.copy(durationMs = known.durationMs) else file
        }
        // Ce que seul l'index connaît (fichier lu par un autre chemin) est gardé aussi
        val walked = viaFiles.mapTo(HashSet()) { "${it.folderPath}/${it.name}".lowercase() }
        val onlyIndexed = viaIndex.filter { "${it.folderPath}/${it.name}".lowercase() !in walked }
        onProgress(merged.size + onlyIndexed.size)
        return assemble(
            volumeId, merged + onlyIndexed,
            if (viaIndex.isEmpty()) "lecture directe" else "lecture directe + index média"
        )
    }

    // ------------------------------------------------- source 1 : index média

    private fun queryMediaStore(volumeId: String, onProgress: (Int) -> Unit): List<Found> {
        val collection: Uri = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(volumeId)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        }.getOrElse { return emptyList() }

        val hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.SIZE)
            add(MediaStore.Video.Media.DURATION)
            @Suppress("DEPRECATION")
            add(MediaStore.Video.Media.DATA)
            if (hasRelativePath) add(MediaStore.Video.Media.RELATIVE_PATH)
        }.toTypedArray()

        val out = mutableListOf<Found>()

        try {
            context.contentResolver.query(
                collection, projection, null, null,
                "${MediaStore.Video.Media.DISPLAY_NAME} COLLATE NOCASE ASC"
            )?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.Video.Media._ID)
                val nameIdx = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(MediaStore.Video.Media.SIZE)
                val durIdx = c.getColumnIndex(MediaStore.Video.Media.DURATION)
                @Suppress("DEPRECATION")
                val dataIdx = c.getColumnIndex(MediaStore.Video.Media.DATA)
                val relIdx = if (hasRelativePath)
                    c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH) else -1

                while (c.moveToNext()) {
                    val id = if (idIdx >= 0) c.getLong(idIdx) else continue
                    val fullPath = if (dataIdx >= 0 && !c.isNull(dataIdx)) c.getString(dataIdx) else null
                    val name = when {
                        nameIdx >= 0 && !c.isNull(nameIdx) -> c.getString(nameIdx)
                        fullPath != null -> fullPath.substringAfterLast('/')
                        else -> continue
                    }
                    if (name.substringAfterLast('.', "").lowercase() !in VIDEO_EXTENSIONS) continue

                    val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                    if (size in 1 until MIN_SIZE_BYTES) continue

                    val folder = when {
                        relIdx >= 0 && !c.isNull(relIdx) -> c.getString(relIdx).trim('/')
                        fullPath != null -> folderFromFullPath(fullPath)
                        else -> ""
                    }
                    if (folder.split('/').any { it in IGNORED_FOLDERS }) continue

                    out += Found(
                        name = name,
                        folderPath = folder,
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        size = size,
                        durationMs = if (durIdx >= 0 && !c.isNull(durIdx)) c.getLong(durIdx) else 0L
                    )
                    if (out.size % 25 == 0) onProgress(out.size)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Index média illisible pour $volumeId : ${e.message}")
            return emptyList()
        }

        onProgress(out.size)
        return out
    }

    /** "/storage/1A2B/Films/Action/film.mkv" -> "Films/Action" */
    private fun folderFromFullPath(fullPath: String): String {
        val parts = fullPath.trim('/').split('/')
        // on retire la racine du volume (/storage/<id> ou /mnt/media_rw/<id>) et le fichier
        val dropFront = when {
            parts.size > 2 && parts[0] == "storage" -> 2
            parts.size > 3 && parts[0] == "mnt" -> 3
            else -> 0
        }
        return parts.drop(dropFront).dropLast(1).joinToString("/")
    }

    // --------------------------------------------- source 2 : lecture directe

    private fun walkMountPoint(volumeId: String, onProgress: (Int) -> Unit): List<Found> {
        val root = StorageAccess.mountPointFor(volumeId)
            ?: StorageAccess.readableMountPoints(context)
                .firstOrNull { it.name.equals(volumeId, ignoreCase = true) }
            ?: run {
                Log.w(TAG, "NFX scan $volumeId : point de montage introuvable")
                return emptyList()
            }
        Log.w(TAG, "NFX scan $volumeId : lecture de ${root.path}")

        val out = mutableListOf<Found>()

        fun walk(dir: File, relative: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            val children = try { dir.listFiles() } catch (e: SecurityException) { null } ?: return
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (child.name in IGNORED_FOLDERS) continue
                    walk(child, if (relative.isEmpty()) child.name else "$relative/${child.name}", depth + 1)
                } else {
                    if (child.extension.lowercase() !in VIDEO_EXTENSIONS) continue
                    val size = child.length()
                    if (size in 1 until MIN_SIZE_BYTES) continue
                    out += Found(
                        name = child.name,
                        folderPath = relative,
                        uri = Uri.fromFile(child).toString(),
                        size = size,
                        durationMs = 0L
                    )
                    if (out.size % 25 == 0) onProgress(out.size)
                }
            }
        }

        walk(root, "", 0)
        onProgress(out.size)
        return out
    }

    // ------------------------------------------- construction de l'arborescence

    fun assemble(volumeId: String, files: List<Found>, source: String): Output {
        val nodes = LinkedHashMap<String, Node>()

        /** Crée les dossiers du chemin s'ils manquent, et renvoie l'id du dernier. */
        fun ensureFolders(path: String): String? {
            if (path.isEmpty()) return null
            var parentId: String? = null
            val builder = StringBuilder()
            for (segment in path.split('/').filter { it.isNotBlank() }) {
                if (builder.isNotEmpty()) builder.append('/')
                builder.append(segment)
                val current = builder.toString()
                val id = nodeId(volumeId, current)
                if (id !in nodes) {
                    nodes[id] = Node(
                        id = id,
                        volumeId = volumeId,
                        parentId = parentId,
                        name = segment,
                        relativePath = current,
                        documentUri = "",
                        isDirectory = true,
                        isAvailable = true
                    )
                }
                parentId = id
            }
            return parentId
        }

        for (f in files) {
            val parentId = ensureFolders(f.folderPath)
            val relative = if (f.folderPath.isEmpty()) f.name else "${f.folderPath}/${f.name}"
            val id = nodeId(volumeId, relative)
            nodes[id] = Node(
                id = id,
                volumeId = volumeId,
                parentId = parentId,
                name = f.name,
                relativePath = relative,
                documentUri = f.uri,
                isDirectory = false,
                sizeBytes = f.size,
                durationMs = f.durationMs,
                isAvailable = true
            )
        }

        // Nombre d'éléments directs par dossier, pour l'affichage des cartes
        val directChildren = nodes.values.groupingBy { it.parentId }.eachCount()
        val withCounts = nodes.values.map { node ->
            if (node.isDirectory) node.copy(childCount = directChildren[node.id] ?: 0) else node
        }

        return Output(
            nodes = withCounts,
            videoCount = withCounts.count { !it.isDirectory },
            folderCount = withCounts.count { it.isDirectory },
            source = source
        )
    }
}
