# Echo-All 0.8

## Pochette et informations automatiques
Dans Musique, menu ⋮ d’un morceau → « Trouver pochette et infos ». Echo-All devine le titre et l’artiste à partir du nom du fichier (modifiables), cherche sur MusicBrainz et affiche les correspondances avec leur pochette, album, année et durée.
- Cocher « Titre, artiste, album » et/ou « Pochette », puis toucher le bon résultat.
- La pochette vient de Cover Art Archive et est enregistrée dans l’appli comme une pochette choisie à la main.
- Les informations trouvées sont conservées : un nouveau scan du téléphone ne les écrase plus.

## Source « Musique et vidéos libres »
Dans Sources → « Musique et vidéos libres ».
- Choisir Musique ou Vidéo, taper une recherche : seuls les contenus du domaine public, sous licence Creative Commons ou dont le partage est autorisé (Live Music Archive) de l’Internet Archive sont proposés. Les éléments en prêt restreint sont exclus.
- La licence est affichée pour chaque résultat (ex. CC BY-NC 3.0 : citer l’auteur, pas d’usage commercial).
- Ouvrir un résultat liste ses pistes ou vidéos (un seul format, dans l’ordre des pistes) ; télécharger un fichier ou tout l’album.
- Téléchargement par Android : notification de progression, continue si l’appli est fermée. Fichiers enregistrés dans Musique/Echo-All ou Films/Echo-All.
- À la fin, le média est ajouté à la bibliothèque avec titre, artiste, album, durée et pochette. Si l’appli était fermée, l’ajout se fait à la prochaine ouverture.
- Android 9 et plus ancien : l’autorisation de stockage est demandée au premier téléchargement.

Le téléchargement depuis YouTube n’est pas proposé (conditions d’utilisation de YouTube et droits d’auteur).

## Validation
Compilation APK, 53 tests unitaires et Lint réussis, zéro erreur Lint (76 avertissements non bloquants). Les URL de recherche construites par l’appli ont été vérifiées sur les services réels (Internet Archive, MusicBrainz). Signature vérifiée avec apksigner : même certificat que l’APK 0.7.
Aucun téléphone connecté : à vérifier sur appareil — téléchargement complet d’une musique et d’une vidéo, ajout automatique à la bibliothèque avec pochette, lecture du fichier téléchargé, application des informations MusicBrainz sur un morceau scanné.

Installer Echo-All-0.8.apk par-dessus la version précédente sans la désinstaller. Identifiant et signature conservés.
