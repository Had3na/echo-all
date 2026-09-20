# Echo-All 0.13 — Notes et cours

Un espace Notes rejoint la barre de navigation Android. Une version Windows de cet espace est fournie séparément.

- Cours manuscrits au stylet, pression, surligneur, gomme par trait, annuler/rétablir, zoom et fonds de page.
- Notes texte, listes à cocher, recherche et classement par matière.
- PDF dès cette version : import, pages portrait/paysage, annotations, conservation hors connexion et export PDF aplati.
- Sauvegarde locale automatique, export/restauration des notes et PDF via l’espace Notes.
- Serveur privé de synchronisation : comptes, révisions, conservation des conflits, transferts de PDF vérifiés et accès HTTPS prévu via Caddy.

## Installation

Android : installer Echo-All-0.13.apk par-dessus la version précédente sans désinstaller.
Windows : extraire Echo-All-Notes-PC-0.13.zip, puis ouvrir Ouvrir-Notes.cmd. Seul l’espace Notes est porté sur PC, pas le lecteur multimédia.

## Limites et étape restante

Aucun hébergement Internet n’est encore configuré : les notes fonctionnent en local et le serveur est prêt à héberger. La synchronisation a été testée entre deux sessions de navigateur via le serveur local, pas entre le téléphone réel et le PC à travers Internet.

Synchronisation pendant que Notes est ouvert ; aucun envoi garanti quand l’application est fermée. Le PDF exporté est aplati en images. Les sauvegardes médias existantes n’incluent pas Notes : utiliser la sauvegarde propre à cet espace.

Le rendu physique, le rejet de paume, les performances au stylet et l’import/export Android via le sélecteur de fichiers restent à tester sur les appareils de l’utilisateur.

Voir [le guide Notes](notes/README.md) pour le lancement Windows, les limites et le déploiement HTTPS.

Validation : 75 tests Android réussis ; 9 tests Notes/serveur réussis, dont un parcours navigateur PC/mobile avec PDF, stylet simulé, hors connexion, modifications pendant envoi, conflits, sauvegarde/restauration et liste à cocher. Lint : 0 erreur / 83 avertissements. Signature APK identique à la 0.12. Dossier Windows autonome démarré et vérifié ; configuration Docker Compose validée.
