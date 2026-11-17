package com.cinetv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.cinetv.data.repo.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CineTvApp : Application(), ImageLoaderFactory {

    val repository: LibraryRepository by lazy { LibraryRepository(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Le thème de couleur choisi dans les Paramètres
        com.cinetv.ui.theme.Ink.apply(com.cinetv.data.prefs.Settings(this).accentTheme)
        // La clé TMDB saisie dans les Paramètres, s'il y en a une
        com.cinetv.data.tmdb.Tmdb.userKey = com.cinetv.data.prefs.Settings(this).tmdbKey
        com.cinetv.data.subs.OpenSubtitles.apiKey = com.cinetv.data.prefs.Settings(this).subtitlesKey
        appScope.launch {
            repository.refreshVolumeAvailability()
            repository.enrichPendingMetadata()
        }
    }

    /**
     * Chargeur d'images dimensionné pour une grille de jaquettes.
     *
     * Le cache par défaut de Coil est taillé pour un téléphone affichant
     * quelques images ; ici une page en montre des dizaines, et les mêmes
     * reviennent à chaque va-et-vient dans l'arborescence. Un quart de la
     * mémoire disponible et un cache disque évitent de redécoder sans cesse,
     * ce qui était une bonne part des à-coups au défilement.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("posters"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .respectCacheHeaders(false)
            .crossfade(220)
            .build()
}
