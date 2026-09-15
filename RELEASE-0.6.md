# Echo-All 0.6

## Pochettes personnalisées
Choisir une image pour un morceau (menu ⋮ de la bibliothèque ou « Changer la pochette » dans le lecteur) ou pour une playlist (onglet Réglages). L’image apparaît sur les cartes de l’accueil, dans la bibliothèque et dans le lecteur. « Retirer » rend la pochette d’origine.

## Transitions Club et Filtre
- Club : les basses du morceau sortant sont coupées pendant que celles du morceau entrant reviennent.
- Filtre : les fréquences du morceau sortant s’atténuent, celles du morceau entrant s’ouvrent progressivement.
Les effets n’ajoutent jamais de gain et respectent l’égaliseur réglé. À la fin du mix, en pause ou en changeant de titre pendant la transition, le morceau retrouve immédiatement son égaliseur habituel. Rendu dépendant de l’égaliseur du téléphone ; sans égaliseur disponible, un fondu normal est appliqué.

## Aléatoire des playlists
Lancer une playlist en aléatoire mélange la lecture sans perdre de titre et sans modifier l’ordre enregistré.

## Studio DJ
Boutons 8, 16, 32 et 64 temps : la durée de transition est calculée à partir du BPM du morceau actif et de la vitesse de lecture, arrondie à la seconde la plus proche (1 à 60 s). Ne cale pas la phase des battements.

## Validation
Compilation APK, 23 tests unitaires et Lint réussis, zéro erreur Lint (79 avertissements non bloquants). Signature vérifiée avec apksigner : même certificat que l’APK 0.5.
Aucun téléphone connecté : rendu sonore des effets Club/Filtre, affichage des pochettes et retour de l’égaliseur en pause pendant un mix restent à vérifier sur appareil.

Installer Echo-All-0.6.apk par-dessus la version précédente sans la désinstaller. Identifiant et signature conservés.
