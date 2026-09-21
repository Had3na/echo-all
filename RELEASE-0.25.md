# Echo-All 0.25 — Studio DJ et Notes sur ordinateur

L'application Windows dispose désormais d'un accueil reprenant la palette rose, les rubriques
et les cartes d'Echo-All mobile, avec une navigation adaptée aux grands écrans.

## Disponible

- Accueil, recherche, musique, vidéos, photos, favoris et playlists locales.
- Ajout de dossiers par le sélecteur Windows, sans modifier les fichiers d'origine.
- Lecteur persistant : file d'attente, précédent/suivant, aléatoire, répétition et volume.
- Vidéos avec reprise de position ; visionneuse photo.
- Studio DJ à deux platines : vrais fichiers audio, formes d'onde, lecture/pause, déplacement
  dans le morceau, quatre CUE mémorisés par titre, boucles de 1/2/4/8 temps, BPM saisi ou tapé,
  synchronisation de tempo, vitesse, trois bandes d'égalisation, gains, crossfader et vumètre.
- Préécoute séparée du mix enregistré, avec choix de sortie audio lorsqu'elle est disponible.
  Pour une vraie écoute séparée, choisir une sortie casque différente de la sortie principale.
- Enregistrement du mix en WebM/Opus, sans microphone. Limite : 90 minutes ou 200 Mo.
- Notes embarqué : texte, listes, stylet, surligneur, gomme, PDF, annotation, export et sauvegardes.
  Notes et le compte s'ouvrent dans des fenêtres séparées pour conserver le mix en cours.
- Thème sombre, clair ou système ; accent personnalisable ; réduction des animations.
- Option Wi-Fi pour Notes, désactivée par défaut et appliquée au redémarrage. Appareils associés
  par code temporaire, protocole chiffré existant. Aucune ouverture de pare-feu automatique.

## Limites connues

Le Studio accepte des fichiers jusqu'à 150 Mo. Les BPM doivent être renseignés ou tapés :
SYNC ajuste le tempo, sans détection automatique de grille ni alignement automatique de phase.
Pas de scratch, de séparation de stems ou de préécoute matérielle garantie sur chaque carte son.
Les formats audio/vidéo dépendent du décodeur Electron.

La bibliothèque PC n'est pas copiée automatiquement dans Firebase. YouTube, les torrents et
l'ensemble du studio de montage Android ne sont pas encore portés sur ordinateur.

Le projet Firebase choisi est echo-all-41fbc, mais l'autorisation CLI et les configurations
Android/Web sont encore nécessaires. Les comptes sociaux ne doivent pas être annoncés comme
activés tant que ces étapes et l'essai de deux comptes réels ne sont pas terminés.

## Vérifications

- 8 tests unitaires PC : bibliothèque, sécurité des chemins, Range, crossfader et tempo.
- Essai Electron avec pistes WAV synthétiques : bibliothèque réelle, décodage, signal audio
  non silencieux, CUE, boucles, préécoute et export d'un fichier WebM valide.
- Notes : texte conservé après rechargement, import d'un PDF et annotation au pointeur.
- Navigation vers le compte puis retour à l'application.
- Construction de l'installateur Windows, logo Echo-All inclus.

Les captures de démonstration utilisent des pistes synthétiques de test ; ces pistes ne sont
pas ajoutées à la bibliothèque de l'utilisateur ni distribuées avec l'application.
