# Serveur PC Echo-All

Serveur Node.js sans dépendance npm, couplé à qBittorrent 5.2.3. L’application Android importe un fichier d’association JSON (adresse HTTPS locale, jeton aléatoire et certificat épinglé). Aucun certificat système ni validation de nom d’hôte n’est désactivé.

## Installation locale actuelle

- Node : C:\Program Files\nodejs\node.exe
- qBittorrent : C:\Program Files\qBittorrent\qbittorrent.exe
- Configuration et clés privées : %LOCALAPPDATA%\Echo-All\TorrentServer (hors dépôt)
- Vidéos : %USERPROFILE%\Videos\Echo-All-PC
- Pont HTTPS : 192.168.1.17:4323 ; pare-feu limité au sous-réseau local
- API qBittorrent : 127.0.0.1:4324 ; authentification locale désactivée uniquement pour le pont local
- Démarrage : Start-Server.ps1, raccourci dans le dossier Démarrage de la session Windows

L’adresse est celle du PC lors de l’installation. Si le routeur la change, il faudra adapter la configuration, le certificat, la règle de pare-feu et le fichier d’association (ou réserver cette adresse dans le routeur).

Prowlarr : installation refusée par la protection Windows ; aucune désactivation ni contournement effectué. Les trois sources existantes sont interrogées directement depuis le PC. Pas de scraping 1337x ajouté.

## Fonctionnement

Les routes /health, /search, /download, /import, /jobs, /jobs/:hash/pause, /jobs/:hash/resume, /jobs/:hash/files et /stream/:hash/:index exigent le jeton Bearer. Les opérations ne concernent que la catégorie qBittorrent echo-all. La lecture accepte uniquement les vidéos terminées, vérifie le chemin réel dans le dossier de téléchargement et supporte les plages HTTP. Pas de transcodage ; la compatibilité dépend du décodeur Android. Un torrent sans pairs peut rester bloqué même sur le PC.

Le fichier pairing.json donne accès au serveur ; ne pas le publier. Les logs ne doivent pas contenir ce jeton. Les fichiers de configuration et certificats sont intentionnellement hors du dépôt.

## Validation

`node --test torrent-server/server.test.mjs`

`node torrent-server/smoke.mjs` utilise la configuration locale ; recherche et ajoute le film libre Sintel uniquement s’il est absent, puis vérifie la fin du téléchargement, les plages vidéo et le refus d’accès sans jeton. Il attend au maximum une minute pour la fin ; un premier téléchargement lent peut nécessiter de relancer le test.

Pour arrêter le serveur, quitter qBittorrent et arrêter uniquement le processus Node exécutant server.mjs. Pour désactiver le démarrage automatique, supprimer le raccourci « Echo-All serveur PC » du dossier Démarrage Windows.
