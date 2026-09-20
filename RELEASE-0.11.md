# Echo-All 0.11 — Vidéos, paroles et navigation animée

## Vidéos
- Deux espaces : Quotidien et Streaming. Les liens HTTPS sont placés par défaut dans Streaming ; les fichiers locaux dans Quotidien.
- Sous-catégories proposées, catégories personnalisées et déplacement entre espaces via le menu ⋮ → Classer la vidéo.
- Le classement est conservé après scan, redémarrage et export/import de sauvegarde.

## Écoute
- Recherche automatique pendant la lecture, gérée par le service même lorsque l’interface est fermée.
- MusicBrainz : correspondance prudente du titre, de l’artiste et de la durée. Les éditions ambiguës restent à choisir manuellement.
- Cover Art Archive : téléchargement d’une pochette seulement si aucune n’a déjà été choisie.
- Les corrections manuelles restent prioritaires. Les recherches peuvent être désactivées dans Réglages → Pendant l’écoute et sont suspendues en mode privé.
- Seuls titre, artiste, album et durée sont envoyés aux services ; aucun fichier audio n’est envoyé.

## Paroles
- Lecteur → Paroles synchronisées : recherche LRCLIB, défilement et surlignage ligne par ligne selon la position réelle du lecteur.
- Toucher une ligne pour s’y déplacer ; bouton de suivi automatique ; décalage réglable de −5 à +5 secondes.
- Import d’un fichier .lrc, cache local et inclusion des paroles et décalages dans les sauvegardes.
- En absence de version synchronisée : texte non synchronisé si disponible ou indication d’indisponibilité. Il ne s’agit pas d’une transcription automatique ni d’un karaoké mot par mot.

## Mouvement
- Navigation flottante arrondie : pastille élastique, changement progressif de couleur et taille des icônes.
- Apparition des pages, pression sur les cartes, transition du titre du mini-lecteur, mouvement de pochette au démarrage/pause et indicateur de lecture animé.
- Les barres sont une animation d’état de lecture, pas une mesure du signal sonore.
- Réduire les animations désactive les transitions ajoutées ; les animations continues s’arrêtent en pause et lorsque l’écran n’est plus actif.

## Références
- Inspiration navigation : https://dribbble.com/shots/26136769-Navigation-bar-liquid-style
- API de paroles : https://lrclib.net/docs
- Animations Compose : https://developer.android.com/develop/ui/compose/animation/choose-api

## Livraison
APK release Echo-All-0.11.apk, versionCode 11. Installer par-dessus la version précédente sans désinstaller.
Aucun téléphone ni émulateur disponible : rendu, fluidité, synchronisation audible et comportement en arrière-plan à valider sur appareil.

Validation PC : build release réussi ; 68 tests, 0 échec ; lint 0 erreur / 81 avertissements ; signature vérifiée identique à la 0.10. Requête réelle LRCLIB vérifiée (47 repères sur le morceau de démonstration).
