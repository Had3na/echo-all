# Echo-All 0.22 — Stockage torrent interne

Le journal ADB du Samsung SM-S721B sous Android 16 montre un abort du service com.google.android.providers.media.module dans libfuse_jni.so, fonction NodeTracker::CheckTracked puis pf_open : « active_nodes_.find(node) != active_nodes_.end() ». Ces événements précèdent les arrêts d’Echo-All par SIGINT aux mêmes heures (17:46:43/44, 18:23:49/50, 18:24:14/15). L’hypothèse ciblée est un problème du stockage externe émulé FUSE utilisé par le moteur torrent.

Les nouveaux téléchargements utilisent désormais filesDir/downloads/torrents, dans le stockage interne privé. À la reprise, les anciens fichiers externes sont copiés dans un dossier temporaire interne puis ce dossier est renommé après copie complète. Les originaux ne sont pas effacés. Si l’espace manque, la copie est refusée avec un message et les originaux restent disponibles. Les fichiers terminés de l’ancienne version restent lisibles à leur emplacement initial. L’export via « Enregistrer sous » est inchangé.

Le message des magnets est corrigé : le dépassement de 90 secondes ne prouve pas l’absence de pairs. Le message indique désormais un délai de réception dépassé et propose une nouvelle tentative ou l’import .torrent.

Tests : migration, conservation des originaux, reprise sans écraser les octets déjà présents en interne, création d’un nouveau dossier interne, ainsi que la suite existante. Pas de réinitialisation ni désinstallation du téléphone.

Validation : 134 tests réussis ; release compilée ; Lint terminé (0 erreur, 110 avertissements). APK ARM64 signé installé par mise à jour sur le Samsung. Lors du contrôle ADB après démarrage du service torrent, le processus est resté présent et le service au premier plan ; aucun nouvel abort FUSE observé dans le journal de suivi. La confirmation de progression et de fin de transfert par l’utilisateur reste attendue.
