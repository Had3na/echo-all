# Echo-All 0.14 — Synchronisation locale et thème unifié

- PC ↔ téléphone sur le même réseau local, sans compte ni hébergement à configurer.
- Le relais est inclus dans l’application PC et démarre avec Notes. Le PC doit rester allumé ; les deux espaces Notes doivent être ouverts.
- Association initiale par code ou QR, valable 5 minutes et une seule fois. Révocation possible depuis le PC.
- Échanges locaux chiffrés et authentifiés ; refus des paquets modifiés ou rejoués. Aucun changement global autorisant HTTP en clair n’a été ajouté à Android.
- Synchronisation automatique toutes les 10 secondes pendant que Notes est visible, avec conservation des conflits et PDF hors connexion.
- Notes Android reprend les couleurs et le thème des réglages Echo-All. Sur PC : logo, palette rose par défaut, thèmes clair/sombre/système, couleurs personnalisables, formes arrondies et léger glow.

## Pour l’utiliser

Installer Echo-All-0.14.apk par-dessus la version actuelle. Sur PC, ouvrir Notes, puis « Même Wi-Fi → Associer mon téléphone ». Sur le téléphone, saisir le code dans « Notes → Même Wi-Fi » ou scanner le QR avec l’appareil photo, puis confirmer l’association.

Windows peut demander l’autorisation du pare-feu. Le script Autoriser-WiFi.cmd crée une règle limitée au moteur Notes, au port TCP 4319 et au sous-réseau local, sur les profils public et privé. Il ne désactive pas le pare-feu et n’ouvre aucun port sur le routeur.

Si l’adresse IP du PC change, refaire l’association. Wi-Fi invité, VPN ou isolation entre appareils peuvent bloquer la connexion. La découverte automatique et IPv6 ne sont pas encore inclus.

## Validation

12 tests Notes/serveur réussis : parcours navigateur, association sans compte via pont Android simulé, synchronisation dans les deux sens, PDF, conflits, fonctionnement hors connexion, sauvegarde/restauration, chiffrement, anti-rejeu, code à usage unique et révocation. Thèmes PC sombre/clair et mobile inspectés visuellement.

Le téléphone physique, le stylet réel et la traversée du pare-feu depuis le téléphone restent à tester. Voir notes/README.md pour le fonctionnement et les limites.

Android : 76 tests, 0 échec, 0 erreur. Build release réussi ; lint 0 erreur / 86 avertissements. Signature identique à la 0.13.

PC en cours mis à jour ; règle Windows autorisée explicitement par l’utilisateur et appliquée au moteur Notes 0.14, TCP 4319, LocalSubnet, profils Public et Private.
