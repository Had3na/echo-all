# Echo-All 0.21 — Source YTS

YTS est interrogé en parallèle des autres sources, via https://movies-api.accel.li/api/v2/list_movies.json, l’API actuellement configurée dans Jackett. La source est activée par défaut et désactivable dans Sources ; ce choix est conservé. Les résultats indiquent l’année, la qualité, le type et la langue disponibles. Les magnets sont construits depuis des hashes validés ; les tailles et seeders participent à l’affichage et au classement existants. Les doublons de hash entre sources sont éliminés.

Le site fourni https://web.yts.gg/ renvoie une page HTML à l’adresse habituelle /api/v2/list_movies.json. L’API distincte ci-dessus a répondu à deux recherches de contrôle, dont une avec résultats. Aucun contenu n’a été téléchargé. YTS est un catalogue de films, pas une source dédiée aux épisodes d’anime.

1337x : la connexion directe à https://1337x.to/search/ a été refusée depuis cet environnement. Aucun connecteur HTML direct non vérifié n’a été ajouté. L’intégration existante Torznab permet d’utiliser l’indexeur 1337x d’un serveur Jackett/Prowlarr déjà configuré : activer l’indexeur sur le serveur, copier son URL Torznab HTTPS dans Sources et renseigner sa clé. Cette marche à suivre figure désormais dans les réglages. Aucun serveur personnel n’a été fourni pour un essai réel de ce chemin.

Références :
- https://github.com/Jackett/Jackett/blob/master/src/Jackett.Common/Definitions/yts.yml
- https://github.com/Jackett/Jackett/blob/master/src/Jackett.Common/Definitions/1337x.yml

Les protections et le diagnostic de la 0.20 sont conservés. La fermeture signalée au lancement d’un torrent sur téléphone reste à diagnostiquer avec le rapport de l’appareil ; l’ajout de sources ne résout pas ce problème.

Validation : 132 tests réussis ; compilation release et Lint terminés. Signatures des APK vérifiées avec le même certificat que la version précédente. Version 0.21.0, code 21. Pas d’essai sur téléphone connecté.
