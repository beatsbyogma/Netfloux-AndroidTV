# Netfloux — médiathèque personnelle pour Android TV

Netfloux parcourt vos disques durs USB et vos dossiers partagés sur le réseau, récupère affiches, logos et résumés sur TMDB, et lit vos vidéos avec son propre lecteur. Elle est pensée pour la télécommande et suit **votre** rangement : elle n'invente aucun classement.

Le projet s'appelle encore `CineTV` dans Android Studio (nom du dossier et du paquet `com.cinetv`) ; l'application, elle, s'appelle Netfloux.

## Ce que fait l'application

**Accueil**
- Grande bannière qui fait défiler des titres de la bibliothèque, avec le logo officiel du film quand il existe.
- Rubriques : Vos disques, Réseau, Ma liste, Reprendre, Récemment ajoutés, Genres, À voir, Sorties récentes. Leur ordre se règle dans Paramètres › Accueil, et chacune peut être masquée.
- « À voir » et « Sorties récentes » proposent des titres **absents des disques** (suggestions TMDB d'après la bibliothèque, et sorties des trois derniers mois). « À voir » change à chaque démarrage (bouton « Autre sélection » pour un nouveau tirage) et n'affiche pas de dessins animés ; les films d'animation restent. Jaquettes verticales par défaut, cartes horizontales au choix dans Paramètres › Affichage. On peut voir la bande-annonce ou écarter un titre.
- La bannière glisse aussi, de temps en temps, une sortie de l'année absente des disques (ni horreur, ni animation) : mention « À télécharger », bouton « Voir la bande-annonce ».
- La taille des cartes se règle rubrique par rubrique (Paramètres › Accueil) ; la bannière, les disques et le réseau gardent la leur. Les rangées de suggestions défilent seules, lentement, tant qu'on n'est pas dessus.
- Un partage réseau peut être rangé parmi les disques (appui long sur le partage).
- Menu de gauche : flèche gauche pour l'ouvrir, flèche droite ou Retour pour en sortir.
- Un dossier peut être « affiché comme un disque » (appui long sur le dossier) : il prend place parmi les disques avec son propre thème et disparaît de son disque d'origine.

**Disques et dossiers**
- Un thème par disque (Films, Séries, Animation, Documentaires, Concerts, Jeunesse, Autre). Sur un thème Séries, chaque dossier est une série : affiche, nombre de saisons, fiche de la série en bannière.
- Grille réglable : nombre de vignettes par ligne et espacement.
- Tri, masquage des titres vus, saut alphabétique.
- Chaque vignette porte la définition du fichier (4K, 1080p, 720p…) et « VU » une fois regardée ; la bannière d'une série annonce la meilleure définition disponible. La définition est lue dans le fichier lui-même, une fois, en arrière-plan (avancement en haut à droite) ; en attendant, c'est celle annoncée par le nom du fichier qui s'affiche.
- Lancer un titre dont le disque est débranché affiche le nom du disque à brancher.
- Un disque débranché reste consultable ; ses fichiers sont simplement indisponibles.

**Fiche d'un titre**
- Lire, reprendre, Ma liste, bande-annonce (lecteur YouTube), corriger la fiche, renommer, supprimer.

**Lecteur**
- Pistes audio et sous-titres, sous-titres en ligne (OpenSubtitles), réglage de leur apparence et de leur décalage.
- Passer l'intro, épisode suivant, aperçu sur la barre de temps, menu des saisons, informations techniques.

**Bibliothèque**
- Statistiques, fichiers non reconnus, export du catalogue en CSV.
- Sauvegarde et restauration de la configuration sur un dossier réseau (fiches, jaquettes choisies, films vus, réglages). Les mots de passe et les clés d'accès n'y figurent jamais.

**Apparence**
- Sept couleurs de thème, écran de veille avec les visuels de la bibliothèque.

## Clés d'accès

- **TMDB** (affiches et résumés) : une clé est fournie dans `app/src/main/java/com/cinetv/data/tmdb/Secrets.kt`. Elle peut être remplacée dans Paramètres › Jaquettes et résumés. **Avant de donner l'APK à quelqu'un, retirer la clé de ce fichier.**
- **OpenSubtitles** (sous-titres en ligne) : clé gratuite à saisir dans Paramètres › Sous-titres en ligne.

## Installer sur la TV

1. Sur la TV : activer les options pour les développeurs, puis le débogage réseau.
2. Sur le PC : `adb connect <adresse de la TV>:5555`.
3. Dans Android Studio : choisir la TV dans la liste des appareils, variante `release`, puis Run.

## Où régler le design

Presque toutes les valeurs visuelles (couleurs, tailles, espacements, durées d'animation) sont regroupées dans `app/src/main/java/com/cinetv/ui/theme/DesignTokens.kt`, avec un commentaire pour chacune.

## Organisation du code

| Dossier | Contenu |
|---|---|
| `data/db` | Base de la bibliothèque (SQLite) |
| `data/repo` | Analyse des disques, recherche des fiches, sauvegarde |
| `data/storage` | Lecture des disques USB, fichiers, sous-titres locaux |
| `data/network` | Partages réseau (SMB) ; mots de passe chiffrés |
| `data/tmdb`, `data/subs` | TMDB et OpenSubtitles |
| `data/prefs` | Réglages |
| `ui/drives`, `ui/home` | Accueil |
| `ui/files` | Disques et dossiers |
| `ui/player` | Lecteur |
| `ui/components` | Cartes, fiche d'un titre, éléments communs |
| `ui/settings`, `ui/library` | Paramètres, statistiques, fichiers non reconnus |
| `ui/theme` | Design |

## Sécurité, en bref

- Les mots de passe des partages réseau sont chiffrés avec une clé gardée dans le coffre matériel de la TV.
- La sauvegarde système d'Android n'emporte ni les partages ni les clés d'accès.
- Supprimer ou renommer un fichier depuis l'application est définitif et demande toujours une confirmation.
- L'application demande l'accès à tous les fichiers : c'est ce qui lui permet de lire les disques USB.


## Clé TMDB

Le dépôt ne contient aucune clé TMDB : `Secrets.kt` est vide. Collez votre propre clé dans Paramètres › Clé TMDB,
ou mettez-la dans `app/src/main/java/com/cinetv/data/tmdb/Secrets.kt` pour une version perso (sans la committer).

Version PC : voir le dépôt [Netfloux - Windows](https://github.com/beatsbyogma/Netfloux-Windows).
