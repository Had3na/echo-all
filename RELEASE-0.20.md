# Echo-All 0.20 — Diagnostic des fermetures torrent

Cette version ne prétend pas avoir reproduit ni résolu le plantage signalé sur téléphone. Aucun appareil n’était connecté. Elle protège les exceptions de démarrage du service Android et fournit le rapport nécessaire pour distinguer exception Java, crash natif, manque de mémoire et blocage.

Dans **Téléchargements → Torrents → Diagnostic du dernier arrêt**, le bouton **Copier** permet de récupérer le rapport. Sous Android 11 et plus, les derniers arrêts conservés par Android sont consultés, y compris ceux de la version précédente si le système les conserve. Les exceptions Java non interceptées sont enregistrées localement pour les prochains incidents. Le rapport contient le modèle du téléphone, la version Android, les classes et piles d’appels ; les messages d’exception, susceptibles de contenir des clés ou URL, sont exclus. Rien n’est envoyé automatiquement.

Les erreurs synchrones de création de notification, de passage au premier plan ou de verrou de veille sont interceptées dans le service, où elles surviennent réellement. L’appelant seul ne pouvait pas les intercepter. Le moteur n’est pas lancé sous Android 8 : libtorrent4j 2.1.0-39 nécessite Android API 28 minimum (https://github.com/aldenml/libtorrent4j/releases/tag/v2.1.0-39).

Installer par-dessus la 0.19 sans désinstaller pour conserver les téléchargements. Si la fermeture persiste, rouvrir l’application et copier le diagnostic. Un crash natif ne produit pas de pile Java ; le rapport Android identifie alors la catégorie de l’arrêt, mais une trace native supplémentaire peut être nécessaire.

Validation : 130 tests réussis, 0 échec, 0 erreur ; compilation release et Lint terminés. Signatures des deux APK vérifiées, certificat identique à la 0.19. Version 0.20.0, code 20. Le comportement sur le téléphone concerné et le diagnostic Android restent à vérifier sur appareil.
