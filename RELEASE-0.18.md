# Echo-All 0.18 — Recherche torrent et téléchargement automatique

## Utilisation

Dans **Films**, la barre de recherche s’ouvre maintenant en mode **Torrents**. Saisir le titre puis valider au clavier ou toucher **Chercher et télécharger**.

La même recherche est disponible dans **Films → Explorer films, séries et animes → Torrents** et dans **Téléchargements → Torrents**. Les fiches TMDB/Jikan restent accessibles dans le mode **Fiches**, et l’ancienne recherche Internet Archive dans **Archives**.

Le réglage **Téléchargement automatique à la validation** est activé par défaut et mémorisé. La recherche se déclenche à la validation, jamais à chaque lettre. Le titre, la source et l’ajout dans la file sont affichés. Pour rechercher sans télécharger automatiquement, désactiver le réglage puis choisir une version dans les résultats.

## Sources

- **The Pirate Bay via APIbay** : recherche dans la catégorie vidéo, affichage des tailles et du nombre de seeders, création du magnet à partir de l’empreinte fournie. APIbay est une interface publique sans garantie de disponibilité ; les blocages réseau ne sont pas contournés.
- **Internet Archive** : recherche de titres vidéo ayant un fichier « Archive BitTorrent » et sans restriction d’accès signalée. Le .torrent est récupéré depuis les métadonnées de l’item. Les noms et licences restent ceux renseignés par les déposants ; ce catalogue ne contient pas tous les films/séries/animes.
- **Autres sites via Torznab** : dans **Sources**, renseigner jusqu’à huit URL de flux HTTPS issues de serveurs Jackett ou Prowlarr déjà configurés. Les flux sont interrogés sur les catégories films/séries. Une clé API commune peut être saisie ; avec plusieurs clés, utiliser les URL complètes fournies par les serveurs et laisser la clé commune vide. L’application n’installe pas ces serveurs et n’ajoute pas leurs indexeurs. Les flux HTTP ne sont pas pris en charge dans cette version.

The Pirate Bay et Internet Archive sont activés par défaut. Chaque source peut être désactivée. Une source indisponible est signalée et n’empêche pas les autres de fournir des résultats. Il ne s’agit pas d’une recherche sur « tous les sites torrent » : seuls ces deux catalogues et les indexeurs exposés par les flux configurés sont interrogés.

Les paramètres de recherche et clés sont stockés dans les préférences privées `torrent_search`, hors des exports des réglages généraux.

## Sélection automatique

Les résultats sont classés par correspondance avec le titre, puis par seeders connus. Les doublons de magnet avec la même empreinte sont regroupés. Les annonces de trailers, samples, bandes originales ou compilations sont écartées du choix automatique sauf si elles ont été explicitement demandées.

Le démarrage automatique exige un titre normalisé exact, ou le titre suivi d’un suffixe de sortie reconnu (année, qualité, langue, saison…). Un résultat dont le nombre de seeders déclaré est zéro ne part pas automatiquement. Un titre voisin ou une suite avec un autre nom reste proposé au choix manuel. Lorsque le nombre de seeders n’est pas renseigné, notamment sur Internet Archive, la disponibilité ne peut pas être garantie.

La sélection est textuelle : aucune correspondance certifiée par identifiant TMDB, langue ou qualité n’est garantie. Si une version précise est nécessaire, compléter la recherche ou désactiver l’automatisme pour la choisir. Aucune correspondance suffisamment précise : la liste s’affiche sans lancer de téléchargement. Douze résultats au maximum sont affichés ; les sources sont interrogées jusqu’à leurs limites configurées (25 Archive, 50 par flux Torznab).

Une recherche répétée ne crée pas une nouvelle tâche pour un résultat déjà connu. Une tâche déjà terminée est signalée ; une tâche en pause peut être reprise. Après le lancement, le moteur torrent 0.17 gère téléchargement, pause, fichiers, import et export comme auparavant. Une panne après sélection n’entraîne pas une cascade de téléchargements d’autres versions.

## Robustesse

Recherches parallèles et indépendantes ; délai maximal de 25 secondes par requête ; réponses limitées ; HTTPS uniquement ; redirections limitées à cinq ; liens de redirection magnet pris en charge pour les fichiers Torznab. Le XML refuse DOCTYPE/ENTITY et ne résout pas les entités externes. Une recherche annulée ne peut pas publier tardivement ses résultats. Le passage à la file est protégé pour éviter qu’une fermeture d’écran laisse une nouvelle tâche sans démarrage du service.

## Validation

Build release réussi ; 127 tests, 0 échec, 0 erreur, dont 12 nouveaux tests de recherche/sélection. Lint : 0 errors, 109 warnings. Les deux APK passent apksigner verify ; le certificat ARM64 est identique à celui de la 0.17. Version : 0.18.0, code 18.

Essais réels de lecture API : APIbay répond aux recherches Ubuntu et Big Buck Bunny ; Internet Archive répond à Night of the Living Dead et fournit le nom de son fichier torrent. Aucun contenu vidéo trouvé pendant ces essais n’a été téléchargé. Les tests du moteur restent réalisés avec des fichiers locaux générés, comme en 0.17.

Aucun téléphone connecté et aucun serveur personnel Jackett/Prowlarr fourni : les formulaires, le lancement de service depuis l’interface Android et l’authentification d’un serveur personnel restent à essayer sur appareil. Les réponses Torznab sont testées sur fixtures XML.

Sources techniques :
- https://github.com/Jackett/Jackett
- https://github.com/Jackett/Jackett/issues/9447
- https://torznab.github.io/spec-1.3-draft/torznab/Specification-v1.3.html
- https://archive.org/advancedsearch.php
