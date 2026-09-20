# Echo-All 0.17 — Téléchargements torrent

## Utiliser

1. Ouvrir **Téléchargements → Télécharger un torrent** (le bouton devient « Torrents · … en cours » lorsqu’une liste existe).
2. Coller un **magnet** et choisir Télécharger, ou sélectionner un fichier **.torrent** sur le téléphone.
3. Consulter la progression, la taille, la vitesse et le nombre de pairs. Un torrent se télécharge à la fois ; les suivants attendent.
4. **Mettre en pause / Reprendre** conserve les données. La reprise vérifie les morceaux présents avant de continuer. Après une interruption du processus ou un redémarrage, rouvrir les torrents et choisir Reprendre.
5. Une fois terminé, **Ajouter les médias à ma bibliothèque** importe les références des vidéos, musiques et images reconnues. **Voir / enregistrer les fichiers** permet de les ouvrir ou de copier chaque fichier vers un emplacement choisi avec **Enregistrer sous**.

## Stockage et réseau

Les données sont stockées dans le dossier externe privé de l’application `Download/torrents/<identifiant>`, avec repli sur le stockage interne si nécessaire. Les autres applications reçoivent uniquement une autorisation de lecture sur le fichier ouvert. Les données privées sont supprimées si Echo-All est désinstallé : enregistrer les fichiers ailleurs pour les conserver indépendamment de l’application. L’export ne déplace pas les originaux. Garder l’écran d’export ouvert pendant une copie ; une copie interrompue peut laisser un fichier incomplet à la destination.

Le protocole échange aussi des morceaux avec d’autres pairs pendant le téléchargement. Le moteur s’arrête à la fin, en pause ou en cas d’échec ; pas de partage prolongé après la fin. Le Wi-Fi et les données mobiles sont utilisables. Une notification affiche le transfert et permet de tout mettre en pause. Le service maintient le processeur actif pendant son exécution (verrou borné à 6 heures). Les restrictions Android/batterie peuvent interrompre un transfert ; les fichiers restent disponibles pour la reprise manuelle. Le service traite également le délai maximal des services dataSync des versions Android récentes.

## Moteur et validation

- libtorrent4j **2.1.0-39**, bibliothèques natives ARM64 et ARM32 incluses. Aucun client torrent externe requis.
- Chargement via les paramètres complets natifs : le raccourci `SessionManager.download(TorrentInfo, …)` perdait les trackers et les sources HTTP dans cette version de la bibliothèque. Le test natif de transfert l’a détecté ; le chargement complet corrige ce cas.
- Les trackers, sources web et pairs explicites du magnet sont conservés après la récupération des métadonnées.
- Magnets avec empreintes v1 hex/base32 et v2 acceptés ; fichiers .torrent limités à 4 Mo et 10 000 entrées. Les chemins sortant du dossier et les liens symboliques sont refusés. Les morceaux sont vérifiés par libtorrent.
- État enregistré dans un journal privé atomique ; les sauvegardes Echo-All existantes ne transportent ni ce journal ni les fichiers torrent. Un arrêt du processus remet les tâches inachevées en pause au prochain chargement.
- Les licences libtorrent4j, libtorrent, OpenSSL et Boost sont embarquées et consultables dans l’écran Torrents.

Contrôles finaux : 115 tests réussis, 0 échec, 0 erreur ; assembleRelease réussi ; Lint : 0 erreur, 107 avertissements. Les APK ARM64 et ARM32 passent apksigner verify. Le certificat ARM64 est identique à celui de la 0.16, permettant une mise à jour en place. Version Android : 0.17.0, code 17.

Tests natifs exécutés sur le PC Windows avec la même version libtorrent4j :

- Vérification et reprise d’un fichier déjà présent.
- Transfert de 128 Kio depuis une source HTTP locale et comparaison exacte du contenu téléchargé.
- Magnet : récupération des métadonnées et transfert de 256 Kio depuis un autre pair local ; comparaison exacte du contenu téléchargé.
- Cinq tests supplémentaires couvrent la validation des magnets, les chemins de fichiers, les types de médias et les états actifs.

**Aucun téléphone connecté** : l’interface Android, le transfert sur un réseau mobile réel, les permissions de notification et les interruptions imposées par un fabricant restent à vérifier sur appareil. Les essais réseau utilisent uniquement des fixtures générées sur le PC, pas de torrent public.

## Limites de cette version

- Tout le contenu du torrent est téléchargé ; pas de sélection de fichiers avant le transfert.
- Pas de recherche de torrents, d’indexeur ni d’association automatique entre les fiches TMDB et des torrents.
- Pas de streaming avant la fin du téléchargement.
- Pas de reprise automatique au démarrage du téléphone : utiliser Reprendre.
- Pas de configuration de proxy, de trackers privés authentifiés ou de limite de débit dans l’interface.
- La disponibilité dépend des pairs, des trackers et du réseau. Un torrent sans source peut rester en attente ; la recherche initiale des métadonnées magnet expire au bout de 90 secondes et peut être relancée.

Sources techniques : https://github.com/aldenml/libtorrent4j et https://github.com/aldenml/libtorrent4j/issues/304.
