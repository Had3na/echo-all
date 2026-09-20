# Echo-All 0.22

Stockage torrent interne pour éviter le plantage FUSE observé sur le Samsung Android 16. [Détails](RELEASE-0.22.md).

# Echo-All 0.21

YTS ajouté aux sources de recherche ; indications pour 1337x via Torznab. [Détails et limites](RELEASE-0.21.md).

# Echo-All 0.20

Protection du démarrage du service torrent et diagnostic local des fermetures sur téléphone. [Détails](RELEASE-0.20.md).

# Echo-All 0.19

Correction de la recherche torrent lors du mélange des résultats The Pirate Bay et Internet Archive. [Détails du correctif](RELEASE-0.19.md).

# Echo-All 0.18

Recherche torrent par titre et téléchargement automatique à la validation. The Pirate Bay/APIbay, Internet Archive et flux Torznab configurables. [Guide et limites](RELEASE-0.18.md).

# Echo-All 0.17

Téléchargements torrent intégrés : magnets, fichiers .torrent, pause/reprise, bibliothèque et export des fichiers. [Guide et limites](RELEASE-0.17.md).

# Echo-All 0.16

Catalogues TMDB/Jikan, films, séries et animes, épisodes, plateformes par pays et liens vidéo personnels. [Installation, configuration et limites](RELEASE-0.16.md).

# Echo-All 0.15

YouTube : recherche, écoute en arrière-plan, playlists, chaînes et téléchargement dans la bibliothèque. [Notes de version](RELEASE-0.15.md).

# Echo-All 0.14

Notes : synchronisation Wi-Fi sans compte et thème Echo-All unifié. [Notes de version](RELEASE-0.14.md).

# Echo-All 0.13

Notes, cours au stylet et PDF : [notes de version](RELEASE-0.13.md).
Version PC et serveur : [guide Notes](notes/README.md).

# Echo-All 0.12

Bibliothèque, lecteur et navigation affinés : [notes de version](RELEASE-0.12.md).

# Echo-All 0.11

Vidéos organisées, paroles et animations : [notes de version](RELEASE-0.11.md).

# Echo-All 0.10

Refonte visuelle : [notes de version](RELEASE-0.10.md).

# Echo-All 0.9

Version actuelle : [notes de version](RELEASE-0.9.md).

Construire l’APK à installer : `gradlew.bat :app:assembleRelease` (nécessite `keystore.properties`, non versionné, qui pointe vers la clé de signature sauvegardée dans `C:\Users\Thomas-yann\Echo-All-cles\`).

# Echo-All 0.8

Notes de version : [RELEASE-0.8.md](RELEASE-0.8.md).

# Echo-All 0.7

Notes de version : [RELEASE-0.7.md](RELEASE-0.7.md).

# Echo-All 0.6

Notes de version : [RELEASE-0.6.md](RELEASE-0.6.md).

# Echo-All 0.5

Notes de version : [RELEASE-0.5.md](RELEASE-0.5.md).

# Echo-All 0.4

Version actuelle : consulter [les notes de version](RELEASE-0.4.md). Les fonctionnalités 0.3 ci-dessous sont conservées.

# Nacre 0.3 — Studio DJ et médiathèque Android

Mise à jour prioritaire pour le téléphone et le DJ. La connexion NAS reste en attente du choix du serveur, conformément au choix de l’utilisateur. Cette version ajoute de nombreuses fonctions ; elle ne contient pas encore toute la liste d’idées initiale.

## Installation

Installer `Nacre-0.3.apk` par-dessus la version 0.2, sans désinstaller. Le numéro de version passe à 3 ; le certificat de signature a été comparé à celui de l’APK 0.2 et correspond. Les données existantes sont conservées par la mise à jour Android ; le modèle de bibliothèque accepte les anciens champs manquants.

## Studio DJ

Ouvrir une musique, toucher le mini-lecteur, puis **Ouvrir le studio DJ**.

- Analyse locale des 90 premières secondes maximum : estimation BPM et enveloppe audio. Aucun envoi de fichier vers un serveur.
- Bouton **TAP**, à partir de quatre appuis, et saisie manuelle de 40 à 240 BPM.
- **SYNC tempo** : adapte la vitesse du prochain titre entre 0,8× et 1,25× à partir des BPM enregistrés. Cela ne synchronise pas la phase des battements.
- Points d’entrée et de sortie mémorisés par média ; bouton **CUE** pour revenir à l’entrée.
- Boucles de 4, 8 ou 16 temps calculées depuis le BPM, à partir de la position courante.
- Courbes douce, linéaire ou coupure ; durée de 0 à 20 secondes. La coupure bascule les volumes au milieu de la fenêtre de transition.
- Préparation du prochain média et curseur manuel A/B entre les deux lecteurs.
- Mode soirée : curseur plus grand et écran maintenu allumé pendant l’ouverture du studio.
- Fermeture du studio pendant un mix manuel : le passage vers B est terminé automatiquement. Une préparation non démarrée est annulée.

### Premier mix manuel

1. Mettre au moins deux morceaux dans la file. Pour tester SYNC, renseigner d’abord le BPM de chacun en le lisant puis en ouvrant le studio.
2. Lire le premier morceau et choisir une durée de transition supérieure à zéro.
3. Appuyer sur **Préparer B**, attendre, puis **Lancer A + B**.
4. Déplacer le curseur de A vers B. **Terminer vers B** achève le fondu.
5. Tester ensuite les points d’entrée/sortie et les boucles sur des morceaux connus.

Les BPM sont des estimations : les rythmes variables et les ambiguïtés moitié/double tempo nécessitent une correction. Les boucles utilisent des déplacements de lecture, avec une précision dépendant du décodage et du téléphone. Ce n’est pas un moteur DJ à précision d’échantillon ; pas de scratch, de grille de battements corrigible ou de préécoute séparée au casque.

## Collections et bibliothèque

Le bouton **Collections** donne accès aux playlists et albums photo.

- Créer, renommer, enregistrer et supprimer une collection.
- Choisir plusieurs médias ; organiser leur ordre avec les flèches.
- Enregistrer une courbe et une durée de transition par collection. Sa lecture applique ce profil au lecteur global.
- Albums photo virtuels : créer une collection composée uniquement de photos.
- Sélections intelligentes : favoris, jamais lus, plus joués, récemment ajoutés.
- Enregistrement de la file en playlist ; modification de son ordre et retrait d’un élément.
- **Lire ensuite** depuis les options d’un média.
- Restauration de la dernière file et position après arrêt du service, sans lecture automatique au redémarrage.
- Recherche et filtre par artiste, album ou dossier lorsque ces informations sont disponibles.
- Pochettes intégrées aux morceaux et miniatures vidéo locales. Sur Android 8.0, les vidéos gardent une icône ; l’extraction redimensionnée nécessite Android 8.1+.
- Scan avec dossiers exclus et durée minimale pour ignorer les sons courts.
- Partage par le sélecteur Android.

Un même fichier accessible par deux URI différentes peut encore apparaître deux fois. Les fichiers privés d’autres applications ou non indexés par Android ne sont pas ajoutés par le scan. Les fichiers manquants d’une collection sont ignorés à la lecture et restent indiqués comme indisponibles dans son édition.

## Vidéo

Les vidéos s’ouvrent maintenant dans un écran dédié.

- Plein écran, commandes Media3 et verrouillage des commandes.
- Fenêtre flottante Picture-in-Picture sur les appareils qui la prennent en charge.
- Double appui à gauche/droite : recul/avance de dix secondes.
- Glissement vertical au bord gauche : luminosité ; au bord droit : volume.
- Ajout de sous-titres externes SRT ou VTT pour la lecture courante.
- Bouton des sous-titres intégrés, selon les pistes du fichier.
- Signets de lecture et capture PNG de l’image actuelle via une destination choisie.
- Fondu depuis le noir au changement de vidéo, désactivable via la réduction des animations.

Les sous-titres externes ne sont pas encore conservés dans la file restaurée. Leur taille et leur décalage ne sont pas personnalisables. Le passage en arrière-plan hors fenêtre flottante met la vidéo en pause.

## Photos

- Albums virtuels et diaporama existant.
- Balayage entre photos à zoom normal ; double appui pour zoomer/revenir au cadrage normal.
- Zoom à deux doigts, déplacement et recentrage.
- Rotation de l’affichage seulement : le fichier original n’est pas modifié.
- Partage et informations de base : dossier, type et référence du fichier.

## Réglages

- Couleur libre conservée ; thèmes sombre, clair ou suivant le système.
- Réduction des animations.
- Égaliseur natif avec profils et cinq réglages de fréquence, adaptés aux bandes du téléphone. Si le système ne fournit pas cet effet, la lecture normale continue.
- Minuterie existante et arrêt à la fin du média.
- Mode privé : ne pas ajouter aux statistiques et ne pas enregistrer de nouvelles reprises/files ; les anciennes données restent présentes tant qu’elles ne sont pas effacées.
- Effacement de l’historique et des reprises.
- Export et restauration JSON de la bibliothèque, des favoris, réglages, collections, points DJ et signets. Les médias eux-mêmes et les autorisations Android ne sont pas inclus.

La restauration fusionne la bibliothèque et remplace les réglages et collections portant les mêmes identifiants. Les restaurations entre téléphones ne peuvent pas rendre automatiquement accessibles les URI du téléphone d’origine. Le minuteur actif et les statistiques de lecture ne sont pas restaurés.

## Validation

Compilation de l’APK et contrôle Lint réussis ; 14 tests unitaires réussis. Le contrôle Lint conserve des avertissements non bloquants. Aucun téléphone connecté et aucun émulateur configuré : pas de validation acoustique, visuelle ou instrumentée. Voir `VALIDATION.md` pour la recette sur appareil et `ROADMAP.md` pour la suite.

## Compilation

Android Studio, SDK 36, JDK 17 ou Java 21 fourni par Android Studio :

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

`local.properties` doit contenir le chemin de votre SDK, par exemple `sdk.dir=C\:/Users/Thomas-yann/AppData/Local/Android/Sdk`.

## Références techniques

- [Commandes personnalisées MediaSession](https://developer.android.com/media/media3/session/control-playback)
- [Décodage local MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)
- [Picture-in-Picture Android](https://developer.android.com/develop/ui/views/picture-in-picture)
