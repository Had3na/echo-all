# Echo-All 0.16 — Catalogues cinéma

Base : version 0.15 existante, conservée. L’APK 0.16 utilise le même identifiant Android et la même configuration de signature.

## Utilisation

Films → Explorer films, séries et animes.

- **Animes / Jikan** : accessible sans clé. Recherche, classement, affiches, synopsis, genres, note, fiche MyAnimeList, bande-annonce lorsqu’elle existe et épisodes paginés. Les titres/synopsis de Jikan ne sont pas toujours en français. Les saisons d’un anime peuvent être des fiches MyAnimeList distinctes ; aucune correspondance hasardeuse avec un identifiant TMDB n’est faite.
- **Films et séries / TMDB** : entrer le **jeton d’accès en lecture**, et non la clé API v3, dans Réglages API. Recherche et populaires, synopsis français lorsqu’il existe, posters, genres, notes, saisons, épisodes et bandes-annonces officielles YouTube.
- **Où regarder / JustWatch via TMDB** : pays configurable (FR par défaut, AE, BE…), abonnements, gratuit, publicité, location et achat. Le bouton ouvre la page d’offres TMDB ; les plateformes peuvent demander un abonnement. L’absence d’offre signifie « non renseigné », pas « indisponible partout ».
- **Lecture** : lecteur Media3/ExoPlayer existant, flux HTTPS fournis par l’utilisateur (MP4, HLS, etc.). Une page StreamTape n’est pas une URL HLS. Les erreurs de lecture sont gérées par le lecteur ; aucune prévalidation serveur des liens n’a été ajoutée. Les bandes-annonces s’ouvrent avec une application compatible.
- Le catalogue Internet Archive reste accessible.

Le jeton TMDB est enregistré dans les préférences privées Android `cinema_api`, hors des exports de Backup.kt. Aucun jeton n’est embarqué dans l’APK ou le dépôt. L’effacer dans Réglages API déconnecte TMDB. Pas de serveur ajouté : les appels partent du téléphone.

## Robustesse

Cache mémoire de 10 minutes, limité à 80 réponses par instance ; requêtes Jikan sérialisées avec au moins 1,1 seconde entre appels ; trois tentatives au maximum pour HTTP 429/502/503/504 ; attente Retry-After numérique respectée, avec message invitant à attendre si elle dépasse 30 secondes. Réponses JSON limitées à 4 Mo. Erreurs séparées pour fiche, plateformes et épisodes, avec boutons Réessayer. Annulation des recherches obsolètes au niveau coroutine. Le transport bloquant termine au plus tard au timeout avant de libérer la requête annulée.

## Services de la proposition qui ne sont pas connectés

- **TheTVDB, OMDb, Kitsu, AniDB** : connecteurs supplémentaires non implémentés ; TMDB/Jikan couvrent le catalogue de cette version. TheTVDB n’est pas une API universellement gratuite : son accès dépend de sa licence ou d’un abonnement.
- **Trakt** : synchronisation du suivi et OAuth non implémentés.
- **StreamTape / Vidstream** : aucun résolveur ni scraping de sites tiers ajouté. La documentation StreamTape décrit des opérations sur des fichiers identifiés et un compte, pas une recherche de film/épisode ni un catalogue Anim-sama.
- **Omnidb.io** : aucune documentation d’API de streaming vérifiée ; aucun endpoint inventé.
- **YouTube Data API / Vimeo API** : non connectées. Les liens de bandes-annonces viennent des métadonnées TMDB/Jikan.

Cette version ne fournit donc pas automatiquement de films ou d’épisodes gratuits à partir de leur titre.

## Sources consultées

- https://developer.themoviedb.org/reference/movie-watch-providers — disponibilités par pays et attribution JustWatch ; pas de flux vidéo ni de liens profonds complets.
- https://developer.themoviedb.org/docs/rate-limiting — l’ancienne limite n’est plus applicable ; respecter HTTP 429.
- https://developer.themoviedb.org/docs/faq — jeton, attribution et usage.
- https://www.themoviedb.org/about/logos-attribution — logo officiel intégré sans modification des tracés ni du dégradé.
- https://docs.api.jikan.moe/ — API REST v4.
- https://www.thetvdb.com/api-information — accès et licence.
- https://strtape.tech/api — documentation StreamTape consultée.

## Validation

Build final : :app:testDebugUnitTest, :app:assembleRelease et :app:lintDebug réussis. 107 tests, 0 échec, 0 erreur, dont 9 nouveaux tests de catalogue. Lint : 0 erreur, 106 avertissements. Les deux APK passent apksigner verify ; le certificat SHA-256 de l’APK ARM64 est identique à celui de la 0.15. La compilation utilise la variante release avec la configuration de signature existante (certificat portant le nom Android Debug). Aucun téléphone connecté et aucun jeton TMDB fourni : l’affichage sur appareil, la lecture vidéo et les appels TMDB authentifiés restent à vérifier. Essai réel Jikan : épisodes de Naruto (ID 20), 100 épisodes reçus et indicateur de page suivante ; une recherche Naruto a renvoyé HTTP 504 côté Jikan/MyAnimeList.
