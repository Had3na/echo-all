# Echo-All 0.7

## Son intégré à l’application
L’égaliseur 5 bandes et les transitions Club et Filtre sont maintenant calculés par Echo-All, et non plus par l’égaliseur du téléphone : même rendu sur toutes les marques, au casque comme en Bluetooth.
- Club : les basses de A disparaissent (jusqu’à −30 dB) juste avant que celles de B reviennent ; les deux basses ne s’additionnent jamais.
- Filtre : A s’amincit (filtre passe-haut qui monte) pendant que B s’ouvre (filtre passe-bas de 350 Hz jusqu’à tout le spectre). Ce sont de vrais filtres, plus seulement 5 bandes.
- Les effets ne font que retirer du son. En fin de mix, en pause ou en changeant de titre pendant une transition, le morceau restant retrouve son volume et son son habituel.

## Logique de transition testée
Le déroulé du mix (automatique, manuel A ↔ B, reprise après le curseur, durée limitée par le point de sortie, déclenchement automatique) est isolé et couvert par des tests.

## Données du studio en base de données
BPM, points d’entrée/sortie, boucles, pads CUE, signets, playlists, historique d’écoute et pochettes sont enregistrés dans une base SQLite au lieu d’un seul gros fichier de préférences. L’appli reste rapide avec une grande bibliothèque.
Au premier lancement de la 0.7, les données de la 0.6 sont transférées automatiquement, en une seule opération ; l’ancien fichier n’est supprimé qu’une fois le transfert terminé.

## Pochettes copiées dans l’application
L’image choisie est copiée et réduite (720 px) dans Echo-All : elle reste affichée même si la photo d’origine est déplacée ou supprimée, et il n’y a plus de limite au nombre de pochettes. Les pochettes choisies avec la 0.6 sont copiées automatiquement au démarrage.
La sauvegarde exportée contient maintenant les pochettes (taille maximale d’une sauvegarde : 40 Mo).

## Cartes de l’accueil
Les symboles (♦, ♥, ♫, E) ont été retirés : il reste la forme de carte. Nouveau réglage dans Personnaliser l’accueil → Forme des cartes : « Carte » ou « Rectangle long » (image à gauche, titre à droite). Compatible avec les cartes compactes.

## Validation
Compilation APK, 41 tests unitaires et Lint réussis, zéro erreur Lint (74 avertissements non bloquants). Signature vérifiée avec apksigner : même certificat que l’APK 0.6.
Aucun téléphone connecté : à vérifier sur appareil — rendu sonore de l’égaliseur et des transitions Club/Filtre, transfert des données de la 0.6 (playlists, BPM, pads, pochettes), affichage des cartes en rectangle long, export/restauration d’une sauvegarde avec pochettes.

Installer Echo-All-0.7.apk par-dessus la version précédente sans la désinstaller. Identifiant et signature conservés.
