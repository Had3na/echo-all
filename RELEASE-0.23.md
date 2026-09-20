# Echo-All 0.23.0

- Playlists : pochettes, artistes, durées, en-tête illustré et menus pour déplacer/retirer les titres. Les playlists existantes sont conservées.
- Vidéo : commandes superposées, masquage automatique, sauts de 10 secondes, progression et vitesse ; fonctions locales de sous-titres, capture, signets et PiP conservées.
- Mémoire : cache des vignettes limité à 12 Mo ; caches d’images vidés quand Android signale le passage en arrière-plan ou une pression mémoire. Le diagnostic distingue les arrêts système, mises à jour et signaux. Cela ne prouve pas que tous les arrêts mémoire sont éliminés.
- Serveur PC : recherche, ajout, progression, pause/reprise et lecture des fichiers vidéo terminés via HTTPS authentifié. Les téléchargements du PC continuent indépendamment du téléphone.

Validation : 134 tests Android, 2 tests serveur, compilation release et lint réussis. Sintel téléchargé entièrement sur qBittorrent ; authentification, recherche, ajout et requêtes de lecture partielles vérifiés.

## Utilisation du serveur installé

Même réseau local, PC allumé : Films → Torrents → Serveur PC → Associer le PC → Téléchargements → Echo-All-Serveur.json. Le fichier a été transféré sur le Samsung. Il contient le secret d’association : ne pas le partager.

Les téléchargements PC sont dans C:\Users\Thomas-yann\Videos\Echo-All-PC. Les anciens téléchargements du téléphone ne sont pas automatiquement transférés. Le serveur démarre à l’ouverture de la session Windows. Il reste limité au réseau local ; la veille ou l’arrêt du PC l’interrompt.

Sources serveur : APIbay, YTS et Internet Archive, plus Sintel de WebTorrent pour les tests. Prowlarr n’a pas été installé : Windows a bloqué son installateur. La disponibilité de chaque source et de ses pairs reste variable.
