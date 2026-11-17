package com.cinetv

import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.cinetv.ui.theme.Motion
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cinetv.data.model.Node
import com.cinetv.data.prefs.Settings
import com.cinetv.data.repo.LibraryRepository
import com.cinetv.data.storage.ExternalSubtitle
import com.cinetv.ui.player.isSeasonFolder
import com.cinetv.ui.player.playerSeasonsOf
import com.cinetv.ui.player.playerInfoOf
import com.cinetv.ui.components.LocalAmbientMotion
import com.cinetv.ui.components.TopRightNotices
import com.cinetv.ui.components.LocalLibrary
import com.cinetv.ui.library.GenreScreen
import com.cinetv.ui.library.StatsScreen
import com.cinetv.ui.library.UnmatchedScreen
import com.cinetv.ui.components.LocalScreenShot
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import com.cinetv.ui.drives.DrivesScreen
import com.cinetv.ui.drives.DrivesViewModel
import com.cinetv.ui.files.BrowseScreen
import com.cinetv.ui.files.BrowseViewModel
import com.cinetv.ui.player.PlayerScreen
import com.cinetv.ui.screensaver.Screensaver
import com.cinetv.ui.search.SearchScreen
import com.cinetv.ui.search.SearchViewModel
import com.cinetv.ui.settings.SettingsScreen
import com.cinetv.ui.settings.SettingsViewModel
import com.cinetv.ui.splash.SplashScreen
import com.cinetv.ui.theme.Ink
import com.cinetv.ui.theme.NetflouxTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Ce qui règle l'arrêt de l'application quand on la quitte. */
object AppExit {
    /** Vrai quand l'application ouvre elle-même un écran d'Android et attend qu'on en revienne. */
    @Volatile var expectReturn = false
    /** Délai entre le départ de l'écran et l'arrêt, en millisecondes : le temps de finir d'enregistrer. */
    const val GRACE_MS = 1200L
}


@UnstableApi
class MainActivity : ComponentActivity() {

    /** Toute interaction repousse l'économiseur d'écran. */
    private val lastInteraction = mutableLongStateOf(System.currentTimeMillis())

    /** L'intro est-elle à l'écran, et combien de fois OK a été pressé pendant. */
    private var splashShowing = true
    private val skipSplash = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val repo = (application as CineTvApp).repository
        com.cinetv.ui.components.NavSound.init(this)

        setContent {
            NetflouxTheme {
                // Le son de navigation d'Android est coupé ici : l'application joue le sien, plus doux
                val rootView = androidx.compose.ui.platform.LocalView.current
                SideEffect { rootView.isSoundEffectsEnabled = false }
                var showSplash by rememberSaveable { mutableStateOf(true) }
                // L'accueil n'est construit qu'une fois le logo posé : le monter
                // dès le départ faisait saccader l'animation d'ouverture.
                var contentReady by rememberSaveable { mutableStateOf(false) }
                SideEffect { splashShowing = showSplash }

                Box(Modifier.fillMaxSize().background(Ink.Void)) {
                    if (contentReady) {
                        CompositionLocalProvider(
                            LocalAmbientMotion provides !showSplash,
                            LocalLibrary provides repo
                        ) {
                            NetflouxNavigation(repo, lastInteraction)
                        }
                        // Annonce d'un disque qui vient de passer en ligne, par-dessus tout écran
                        if (!showSplash) TopRightNotices(repo)
                    }

                    AnimatedVisibility(
                        visible = showSplash,
                        enter = fadeIn(tween(0)),
                        exit = fadeOut(tween(380))
                    ) {
                        SplashScreen(
                            onFinished = { contentReady = true; showSplash = false },
                            onSettled = { contentReady = true },
                            skipSignal = skipSplash.intValue,
                            preload = {
                                // La base est lue une première fois ici : l'accueil la trouvera chaude
                                repo.volumes()
                                repo.continueWatching()
                                repo.recentlyAdded()
                                repo.randomSpotlight()
                            }
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- arrêt complet
    // Quitter Netfloux — par « Quitter », par la touche Accueil de la télécommande ou en
    // lançant autre chose — arrête vraiment l'application : la fenêtre est retirée des
    // applications récentes et le processus s'arrête. Rien ne reste en arrière-plan.

    private val exitHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val exitNow = Runnable {
        runCatching { finishAndRemoveTask() }
        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(0)
    }

    override fun onStop() {
        super.onStop()
        // Sauf si l'on attend de revenir (réglage Android ouvert depuis l'application),
        // ou si l'écran ne fait que se reconstruire
        if (!isChangingConfigurations && !AppExit.expectReturn) {
            exitHandler.postDelayed(exitNow, AppExit.GRACE_MS)
        }
    }

    override fun onStart() {
        super.onStart()
        // Revenu avant l'échéance : on reste
        exitHandler.removeCallbacks(exitNow)
        AppExit.expectReturn = false
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        lastInteraction.longValue = System.currentTimeMillis()
        // Pendant l'intro, OK la fait passer — et l'appui n'atteint pas l'accueil caché derrière
        if (splashShowing) {
            val ok = event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                event.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            if (ok) {
                if (event.action == android.view.KeyEvent.ACTION_UP) skipSplash.intValue++
                return true
            }
        }
        com.cinetv.ui.components.NavSound.onKey(event)
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        lastInteraction.longValue = System.currentTimeMillis()
        return super.dispatchTouchEvent(event)
    }
}

@UnstableApi
@Composable
private fun NetflouxNavigation(
    repo: LibraryRepository,
    lastInteraction: MutableLongState
) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = remember { Settings(context) }

    // Lecture demandée sur un disque débranché : on dit lequel rebrancher, au lieu d'ouvrir le lecteur
    var unplugged by remember { mutableStateOf<Pair<String, Boolean>?>(null) }     // nom du disque, réseau ou non
    val play: (Node) -> Unit = { node ->
        scope.launch {
            val current = repo.node(node.id) ?: node
            if (current.isAvailable) {
                com.cinetv.ui.components.NavSound.launch()
                nav.navigate("play/${current.id}")
            }
            else {
                val label = runCatching { repo.volumes() }.getOrDefault(emptyList())
                    .firstOrNull { it.id == current.volumeId }?.label.orEmpty()
                unplugged = label to current.volumeId.startsWith("smb:")
            }
        }
    }

    // Économiseur d'écran
    var idle by remember { mutableStateOf(false) }
    var backdrops by remember { mutableStateOf<List<String>>(emptyList()) }
    var titles by remember { mutableStateOf<List<String>>(emptyList()) }
    var logos by remember { mutableStateOf<List<String>>(emptyList()) }
    var playing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            val minutes = settings.screensaverMinutes
            if (minutes > 0 && !playing) {
                val elapsed = System.currentTimeMillis() - lastInteraction.longValue
                if (elapsed > minutes * 60_000L && !idle) {
                    val art = repo.recentBackdrops()
                    if (art.isNotEmpty()) {
                        backdrops = art.map { it.second }
                        titles = art.map { it.first }
                        logos = art.map { it.third }
                        idle = true
                    }
                }
            }
        }
    }

    LaunchedEffect(lastInteraction.longValue) { if (idle) idle = false }

    // Tout l'écran passe par ce calque, pour pouvoir être photographié à la demande
    val screenLayer = rememberGraphicsLayer()
    val screenShot: suspend () -> ImageBitmap? = remember(screenLayer) {
        { runCatching { screenLayer.toImageBitmap() }.getOrNull() }
    }

    // Retour depuis l'accueil ouvre « Quitter Netfloux ? ». Déclaré avant les écrans : tout ce
    // qu'ils gèrent eux-mêmes avec Retour (refermer le menu de gauche…) garde la priorité.
    val here by nav.currentBackStackEntryAsState()
    var quitAsk by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = here?.destination?.route == "drives" && !idle) {
        quitAsk = true
    }

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalScreenShot provides screenShot) {
        NavHost(
            navController = nav,
            startDestination = "drives",
            modifier = Modifier
                .fillMaxSize()
                .background(Ink.Void)
                .drawWithContent {
                    screenLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(screenLayer)
                },
            // On avance : la nouvelle page monte en fondu, l'ancienne recule.
            // On revient : l'inverse.
            // On entre : on plonge dans la page — elle grandit en apparaissant, l'ancienne
            // grossit en s'effaçant. On ressort : le mouvement inverse.
            enterTransition = {
                fadeIn(tween(Motion.pageInMs, Motion.pageInDelayMs)) +
                    scaleIn(tween(Motion.pageInMs, Motion.pageInDelayMs, FastOutSlowInEasing), initialScale = Motion.pageDeep)
            },
            exitTransition = {
                fadeOut(tween(Motion.pageOutMs)) +
                    scaleOut(tween(Motion.pageOutMs, easing = FastOutSlowInEasing), targetScale = Motion.pageNear)
            },
            popEnterTransition = {
                fadeIn(tween(Motion.pageInMs, Motion.pageInDelayMs)) +
                    scaleIn(tween(Motion.pageInMs, Motion.pageInDelayMs, FastOutSlowInEasing), initialScale = Motion.pageNear)
            },
            popExitTransition = {
                fadeOut(tween(Motion.pageOutMs)) +
                    scaleOut(tween(Motion.pageOutMs, easing = FastOutSlowInEasing), targetScale = Motion.pageDeep)
            }
        ) {

            composable("drives") {
                val vm: DrivesViewModel = viewModel(factory = factory { DrivesViewModel(repo) })
                DrivesScreen(
                    viewModel = vm,
                    onOpenDrive = { volume -> nav.navigate("browse/${Uri.encode(volume.id)}") },
                    onOpenGenre = { genre -> nav.navigate("genre/${Uri.encode(genre)}") },
                    onOpenVirtual = { volumeId, folderId ->
                        nav.navigate("browse/${Uri.encode(volumeId)}?folderId=$folderId")
                    },
                    onPlay = { node -> play(node) },
                    onOpenFolderOf = { node ->
                        val folder = node.parentId
                        nav.navigate(
                            "browse/${Uri.encode(node.volumeId)}" +
                                if (folder != null) "?folderId=$folder" else ""
                        )
                    },
                    onOpenSearch = { nav.navigate("search") },
                    onOpenSettings = { nav.navigate("settings") }
                )
            }

            composable("search") {
                val vm: SearchViewModel = viewModel(factory = factory { SearchViewModel(repo) })
                SearchScreen(
                    viewModel = vm,
                    onPlay = { node -> play(node) },
                    onOpenFolderOf = { node ->
                        val folder = node.parentId
                        nav.navigate(
                            "browse/${Uri.encode(node.volumeId)}" +
                                if (folder != null) "?folderId=$folder" else ""
                        )
                    }
                )
            }

            composable("settings") {
                val vm: SettingsViewModel = viewModel(factory = factory { SettingsViewModel(repo, context) })
                SettingsScreen(
                    viewModel = vm,
                    onOpenStats = { nav.navigate("stats") },
                    onOpenUnmatched = { nav.navigate("unmatched") }
                )
            }

            composable(
                route = "genre/{name}",
                arguments = listOf(navArgument("name") { type = NavType.StringType })
            ) { entry ->
                GenreScreen(
                    genre = entry.arguments?.getString("name").orEmpty(),
                    onPlay = { node -> play(node) },
                    onOpenFolderOf = { node ->
                        val folder = node.parentId
                        nav.navigate(
                            "browse/${Uri.encode(node.volumeId)}" +
                                if (folder != null) "?folderId=$folder" else ""
                        )
                    }
                )
            }

            composable("stats") { StatsScreen() }
            composable("unmatched") { UnmatchedScreen() }

            composable(
                route = "browse/{volumeId}?folderId={folderId}",
                arguments = listOf(
                    navArgument("volumeId") { type = NavType.StringType },
                    navArgument("folderId") {
                        type = NavType.StringType; nullable = true; defaultValue = null
                    }
                )
            ) { entry ->
                val volumeId = entry.arguments?.getString("volumeId").orEmpty()
                val folderId = entry.arguments?.getString("folderId")
                val vm: BrowseViewModel = viewModel(
                    key = "browse_${volumeId}_${folderId ?: "root"}",
                    factory = factory { BrowseViewModel(repo, context, volumeId, folderId) }
                )
                BrowseScreen(
                    viewModel = vm,
                    onOpenFolder = { folder -> nav.navigate("browse/${Uri.encode(volumeId)}?folderId=${folder.id}") },
                    onPlay = { file -> play(file) }
                )
            }

            composable(
                route = "play/{nodeId}",
                arguments = listOf(navArgument("nodeId") { type = NavType.StringType }),
                // Le lecteur s'ouvre et se referme en simple fondu
                enterTransition = { fadeIn(tween(Motion.playerFadeMs)) },
                exitTransition = { fadeOut(tween(Motion.pageOutMs)) },
                popEnterTransition = { fadeIn(tween(Motion.playerFadeMs)) },
                popExitTransition = { fadeOut(tween(Motion.playerFadeMs)) }
            ) { entry ->
                val nodeId = entry.arguments?.getString("nodeId").orEmpty()
                var node by remember { mutableStateOf<Node?>(null) }
                var next by remember { mutableStateOf<Node?>(null) }
                var subtitles by remember { mutableStateOf<List<ExternalSubtitle>>(emptyList()) }
                var siblings by remember { mutableStateOf<List<Node>>(emptyList()) }

                LaunchedEffect(nodeId) {
                    val item = repo.node(nodeId)
                    // Les sous-titres posés à côté du film sont cherchés avant de lancer la lecture
                    subtitles = item?.let { repo.externalSubtitles(it) }.orEmpty()
                    // Le menu des épisodes : le dossier de l'épisode et, s'il s'agit
                    // d'un dossier de saison, ceux des saisons rangées à côté
                    siblings = item?.let { file ->
                        val folder = file.parentId?.let { repo.node(it) }
                        val folders = if (folder != null && isSeasonFolder(folder.name)) {
                            repo.children(file.volumeId, folder.parentId)
                                .filter { it.isDirectory && isSeasonFolder(it.name) }
                                .map { it.id }
                        } else {
                            listOf(file.parentId)
                        }
                        folders.flatMap { repo.children(file.volumeId, it) }.filter { !it.isDirectory }
                    }.orEmpty()
                    node = item
                    next = item?.let { repo.nextInFolder(it) }
                }

                DisposableEffect(nodeId) {
                    playing = true
                    // Pendant un film, les relevés de fond laissent le disque tranquille
                    repo.playbackActive = true
                    com.cinetv.ui.components.NavSound.muted = true
                    onDispose { playing = false; repo.playbackActive = false; com.cinetv.ui.components.NavSound.muted = false }
                }

                node?.let { item ->
                    PlayerScreen(
                        uri = item.documentUri,
                        title = item.displayTitle,
                        startPositionMs = item.lastPositionMs,
                        nextTitle = next?.displayTitle,
                        info = remember(item.id) { playerInfoOf(item) },
                        seasons = remember(item.id, siblings) { playerSeasonsOf(item, siblings) },
                        currentEpisodeId = item.id,
                        nextThumbUrl = next?.let { it.backdropUrl.ifBlank { it.posterUrl } }.orEmpty(),
                        sizeBytes = item.sizeBytes,
                        onPickEpisode = { id ->
                            nav.navigate("play/$id") { popUpTo("play/$nodeId") { inclusive = true } }
                        },
                        externalSubtitles = subtitles,
                        onFindSubtitles = { repo.findOnlineSubtitles(item) },
                        onFetchSubtitle = { found -> repo.fetchOnlineSubtitle(item, found) },
                        onProgress = { pos, dur -> scope.launch { repo.savePosition(item.id, pos, dur) } },
                        onFinished = { scope.launch { repo.setWatched(item.id, true) } },
                        onPlayNext = {
                            next?.let { following ->
                                nav.navigate("play/${following.id}") {
                                    popUpTo("play/$nodeId") { inclusive = true }
                                }
                            } ?: nav.popBackStack()
                        },
                        onExit = { nav.popBackStack() }
                    )
                }
            }
        }

        }

        unplugged?.let { (label, network) ->
            com.cinetv.ui.components.UnpluggedNotice(label, network) { unplugged = null }
        }

        // Retour depuis l'accueil : on demande avant de quitter, et quitter arrête vraiment l'application
        if (quitAsk) CompositionLocalProvider(LocalScreenShot provides screenShot) {
            com.cinetv.ui.components.QuitDialog(
                onQuit = {
                    // La fenêtre se ferme et sort des applications récentes, puis le processus s'arrête :
                    // plus rien ne tourne en fond.
                    // La fenêtre se ferme et sort des applications récentes ; l'arrêt du processus suit
                    // aussitôt (voir onStop dans MainActivity), plus rien ne tourne en fond.
                    AppExit.expectReturn = false
                    (context as? android.app.Activity)?.finishAndRemoveTask()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        {
                            android.os.Process.killProcess(android.os.Process.myPid())
                            kotlin.system.exitProcess(0)
                        }, 300
                    )
                },
                onDismiss = { quitAsk = false }
            )
        }

        if (idle) {
            Screensaver(
                backdrops = backdrops,
                titles = titles,
                logos = logos,
                onWake = { idle = false; lastInteraction.longValue = System.currentTimeMillis() }
            )
        }
    }
}

/** Fabrique de ViewModel minimaliste — évite d'ajouter Hilt pour quelques écrans. */
private fun <VM : ViewModel> factory(builder: () -> VM) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = builder() as T
}
