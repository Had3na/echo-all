# Echo-All 0.4

## Ajouts
- Nom Echo-All et logo fourni, affichés dans l’application et sur le lanceur Android.
- Dock arrondi de 52 dp, rubrique active libellée, accès Collections dans l’en-tête compact.
- Couleur rose violet par défaut pour une installation neuve ; couleur personnalisée existante conservée.
- Collections enregistrées à chaque modification, sélection conservée à la rotation après modification.
- Modification d’une collection sans changer sa place dans la liste ; dédoublonnage des références.
- Duplication, lecture mélangée, ajout/retrait groupé des résultats et annulation de la dernière suppression tant que l’écran reste ouvert.
- Ouverture dédiée des playlists vidéo et accès explicite aux photos des collections mixtes.
- Signalement des références absentes, sans les effacer. Une entrée JSON de collection invalide n’empêche plus de lire les autres entrées valides.
- Quatre pads CUE par morceau : poser, rappeler, effacer. Ils utilisent les recherches de position Media3, sans précision au sample garantie.
- Retrait de l’encart NAS de l’application.

## Validation
assembleDebug, testDebugUnitTest et lintDebug réussis. 16 tests, zéro échec. Lint : zéro erreur, 67 avertissements.
Aucun appareil Android disponible : ergonomie, transitions audibles et mise à jour des données restent à vérifier sur téléphone.

## Installation
Installer Echo-All-0.4.apk par-dessus Nacre. L’identifiant fr.nacre.media reste identique pour permettre la mise à jour. Ne pas désinstaller l’ancienne application avant.

## Travail restant
Les idées de ROADMAP.md ne sont pas toutes livrées : analyse BPM en lot, synchronisation de phase, préécoute casque, scratch, édition EXIF et widgets restent à développer. Le NAS est exclu du périmètre actuel.
