# Echo-All 0.15 — YouTube

Nouvel espace **Sources → YouTube** : rechercher, écouter, ouvrir une playlist ou une chaîne, et télécharger dans la bibliothèque.

## Ce que ça fait

- Recherche par **Musique**, **Vidéos**, **Playlists** ou **Chaînes**.
- Écoute audio dans le lecteur Echo-All habituel : arrière-plan, écran verrouillé, file d’attente, studio DJ, égaliseur, fondus enchaînés. Rien de spécifique à YouTube côté lecture.
- Vidéo dans l’écran vidéo existant : plein écran, PiP, signets.
- Ouverture d’une playlist ou d’une chaîne : tout écouter, lecture aléatoire, tout télécharger.
- Collage d’un lien, et **Partager vers Echo-All** depuis l’application YouTube.
- Téléchargement vers `Musique/Echo-All` ou `Films/Echo-All`, avec titre, artiste et pochette, puis ajout automatique à la bibliothèque — comme les téléchargements Internet Archive.
- Réglages ⚙ : qualité audio, format (origine / M4A / MP3), qualité vidéo, et **mise à jour de yt-dlp**.

## Sur l’accueil

Un bloc YouTube sous le titre : on tape, on écoute, on télécharge. L’anneau à droite de chaque
résultat se remplit pendant le téléchargement, puis une coche apparaît et le morceau rejoint la
bibliothèque. Playlists, chaînes, vidéos et réglages restent dans l’espace complet, sous Sources.

## Robustesse

- **Lien expiré** : YouTube révoque une URL de flux sans prévenir. L’URL résolue étant gardée une
  heure pour éviter de la redemander à chaque avance rapide, un lien mort rendait le morceau
  injouable pendant tout ce temps — relancer la lecture rejouait la même URL morte. Le lecteur
  vide maintenant l’entrée et retente une fois, une seule par morceau pour qu’une vidéo
  réellement supprimée ne boucle pas.
- **yt-dlp périmé** : le moteur livré dans l’APK prend du retard sur YouTube en quelques semaines,
  et tout téléchargement échoue alors en 403. Il se met à jour seul, au plus une fois par semaine,
  en Wi-Fi, juste avant un téléchargement. Le bouton manuel reste et partage la même horloge.

## Confort

- **Annuler.** Retirer un média ou classer une vidéo laisse un bandeau avec un bouton « Annuler ».
  L’annulation d’un retrait ne démasque que ce média, là où « Réafficher les médias masqués » les
  ramène tous. Les messages annulables passent par un canal séparé des messages ordinaires, pour
  qu’un message arrivant entre-temps ne puisse jamais hériter du bouton d’un autre.
- **Centre de téléchargements**, dans Sources : YouTube et Internet Archive au même endroit, relance
  d’un échec sans avoir à retrouver le titre, et place occupée par les fichiers téléchargés. La
  taille est lue dans l’index média : depuis Android 10, une application ne peut plus lister
  `Musique/` par chemin. Cet écran ne supprime rien, il informe.
- **Garder un titre YouTube sans le télécharger.** L’entrée pointe sur l’adresse `watch?v=…`, comme
  une radio pointe sur son flux : le lecteur sait déjà résoudre cette adresse, donc le titre devient
  favorisable, classable en playlist et repris à l’accueil, pour quelques octets au lieu de plusieurs
  mégaoctets. Sans connexion il ne se lit pas, contrairement à un fichier téléchargé.
- **Sélection multiple.** Appui long sur un média : cases à cocher, puis ajout à une playlist
  existante ou nouvelle, ou retrait de tout le lot avec une seule annulation.
- **Historique de recherche.** Les six dernières recherches en pastilles, sur l’accueil et dans
  l’espace complet. Elles s’effacent dès que des résultats s’affichent.
- **File de lecture à un geste.** Une icône dans le mini-lecteur, au lieu d’un bouton texte au fond du
  lecteur plein écran. Le dialogue est le même, sorti dans son propre composable.
- **Déjà téléchargé.** Chaque fichier retenu note l’identifiant de sa vidéo, et la recherche affiche
  une coche grise au lieu de la flèche — sans quoi un morceau déjà pris revenait à neuf après chaque
  redémarrage et se téléchargeait deux fois. La coche reste cliquable : un fichier supprimé ailleurs
  peut être repris.

## Tempo et enchaînement

- **Le BPM se mesure tout seul.** L’analyse existait déjà mais il fallait ouvrir le studio DJ sur
  chaque morceau. Le lecteur demande maintenant la mesure du titre en cours et de celui d’après, en
  arrière-plan et un à la fois, quand « Caler le tempo automatiquement » est actif. Le réglage a été
  remonté dans les réglages principaux ; il était enterré dans le studio.
- **Le calage de tempo ne dénature plus le son.** Il acceptait jusqu’à 0,8× et 1,25× : un quart plus
  vite ou un cinquième plus lent, ce n’est plus du calage, c’est un autre morceau. La limite est
  désormais de **±6 %**, mesurée par rapport à la vitesse déjà entendue — une vitesse choisie à la
  main est donc conservée telle quelle, seul l’ajustement posé par-dessus est borné. Le résultat ne
  sort jamais de 0,5×–2×.
- **La moitié et le double du tempo comptent comme un accord.** Un titre à 90 BPM sous un titre à
  178 demandait auparavant un étirement de 98 % et était abandonné ; compté à double temps, l’écart
  réel est de 1 %. Beaucoup d’enchaînements se font maintenant avec un ajustement minuscule.
- **Une vitesse ne se transmet plus au morceau suivant.** Quand aucun calage n’était possible, la
  platine suivante héritait de l’ajustement calculé pour le morceau précédent : un titre se retrouvait
  accéléré ou ralenti sans raison qui le concerne, et cela se propageait le long de la file. Sans
  calage possible, le morceau garde sa vitesse, explicitement.
- **Aléatoire intelligent**, actif par défaut : chaque titre suit celui qui lui va le mieux, d’abord
  par écart de tempo, avec une forte pénalité sur le même artiste deux fois de suite et une
  préférence douce pour le même genre. Un peu de bruit évite d’obtenir deux fois le même ordre.
- **Le genre a été ajouté au scan.** Android ne l’expose qu’à partir de la version 11, et il faut
  relancer un scan pour que les morceaux déjà présents le récupèrent.

Un titre YouTube joué en streaming n’aura jamais de BPM : l’analyse décode un fichier local, pas un
flux. Il participe au mélange sur son artiste et son genre seulement. Un titre téléchargé est un
fichier ordinaire, donc mesuré comme les autres.

## Recherche depuis l’accueil

Un filtre dans la barre de recherche : Musique, Vidéos, Playlists ou Chaînes. Une vidéo s’ouvre dans
l’écran vidéo ; une playlist ou une chaîne ouvre l’espace complet sur elle.

## Films

Un écran **Films** dans Sources, construit sur le catalogue Internet Archive que l’application
interrogeait déjà, mais présenté comme une vitrine au lieu d’une liste.

- Une affiche à la une, puis sept rayons par genre : longs métrages, science-fiction, animation,
  film noir, comédie, aventure, documentaires. Les sept requêtes partent en parallèle.
- Une fiche par film : bandeau, synopsis, durée, licence, et les autres encodages ou épisodes en
  dessous.
- **La lecture directe**, qui manquait : l’écran précédent ne savait que télécharger. Les fichiers
  de l’Archive sont des mp4 en HTTPS, le lecteur les lit en flux.
- Le **synopsis** n’était récupéré nulle part ; il est demandé à la recherche et son HTML est nettoyé.

Les vignettes de l’Archive ne sont pas des affiches au format portrait mais des images extraites, de
proportions variables. Les cartes sont donc en 16/9 : de faux formats d’affiche auraient déformé les
images. De vraies affiches demanderaient une source de métadonnées comme TMDB.

Les garde-fous de licence de la recherche s’appliquent à chaque rayon : mêmes filtres, et jamais
d’élément en prêt restreint.

## Calage sur la musique

- **Le niveau est pré-mesuré.** La loudness n’était connue qu’après avoir joué un morceau une fois,
  donc sa toute première transition se faisait sans alignement de volume : un titre masterisé fort
  débarquait sur le précédent. L’analyse décodait déjà 90 secondes ; elle alimente maintenant le
  même mètre BS.1770 dans la même passe. Une valeur déjà recueillie sur une lecture complète n’est
  jamais écrasée.
- **Le fondu dure un nombre entier de temps.** Six secondes à 128 BPM, c’était 12,8 temps : la
  transition se terminait au milieu d’un temps. Elle est arrondie, en préférant les groupes de
  quatre quand ils sont à moins d’un temps de ce qui a été demandé.
- **Les temps des deux morceaux tombent ensemble.** Caler le tempo sans caler la phase donne deux
  titres à la même vitesse dont les batteries se dédoublent. Un peigne sur l’enveloppe d’attaques
  repère où commence la grille de temps, à 10 ms près, et la platine entrante est décalée d’au plus
  un demi-temps au moment où elle prend la main — pas à sa préparation, sinon la sortante aurait
  avancé entre-temps et le calcul serait déjà faux.

Ces trois points demandent « Caler le tempo automatiquement ». La précision finale de l’alignement
dépend de la gigue du `seekTo` d’ExoPlayer, qui n’a pas été mesurée sur téléphone : c’est le point
à vérifier à l’oreille.

Le limiteur de crêtes, lui, existait déjà dans la chaîne audio et protégeait déjà la sortie.

## Mixage

Le fondu enchaîné passe à **puissance constante**. Jusqu’ici tous les styles utilisaient une somme
constante : à mi-parcours chaque piste à 0,5. Deux morceaux différents s’additionnent en puissance et
non en amplitude, la transition perdait donc environ 3 dB en son milieu — le creux qu’un fondu est
censé ne pas avoir. Les gains valent maintenant `√(1−b)` et `√b`, soit 0,707 chacun au centre, et la
sonorité reste égale d’un bout à l’autre. Même loi pour le curseur manuel du studio DJ.

Le grave est traité sur **tous** les fondus, et plus seulement sur le style « club ». Pendant le
recouvrement, la basse du morceau qui arrive reste en retrait pendant que celle du morceau qui part
descend : à mi-parcours, 3 dB de moins sur chacune. Deux sources de même niveau s’additionnent
d’environ 3 dB, donc le grave combiné retrouve exactement le niveau qu’il avait sur un seul morceau,
au lieu de doubler. C’est ce cumul qui rendait les transitions boueuses. Aux deux extrémités, le
morceau resté seul est entendu sans aucun traitement, et « coupure » reste neutre puisqu’un passage
franc n’a pas de recouvrement à nettoyer.

Le style **« club »** a été repris sur le même principe. Ses deux courbes étaient décalées : la basse
du morceau qui part descendait de 30 à 55 % de la transition, celle du morceau qui arrive ne
remontait qu’à partir de 45 %. Entre les deux, les deux graves étaient coupés en même temps et le
total tombait à **−24 dB** en plein milieu — le mix se vidait par le bas exactement là où il aurait
dû être le plus plein. Les courbes se croisent maintenant au même instant et se partagent le grave
en puissance : ce que l’une lâche, l’autre le prend, et le creux ne dépasse plus **−1,3 dB**.
L’échange reste franc, la basse du morceau qui arrive étant coupée jusqu’à 35 % puis rendue à 65 %.

Le test ne pouvait pas voir ce défaut : il vérifiait que la somme ne dépasse jamais 1, jamais qu’elle
ne s’effondre. Il mesure désormais ce qui parvient réellement aux oreilles, gain de platine compris.

Le filtrage des fondus ordinaires est un low-shelf à 220 Hz de 6 dB au maximum, réparti sur toute la transition : il ne
doit pas s’entendre agir, contrairement au « club » qui coupe à 30 dB et s’assume comme un effet.

Les styles (douce, linéaire, coupure, club, sweep) gardent leur rôle : ils décident **quand** le fondu
avance ; la loi de gain décide **de combien**. Contrepartie : deux morceaux quasi identiques qui se
recouvrent peuvent culminer environ 3 dB plus haut, ce qui ne se produit que si un titre s’enchaîne
sur lui-même. Trois fichiers de tests vérifient désormais la puissance au lieu de la somme.

## Réseau et pannes

- **Un morceau illisible ne stoppe plus la file.** Après la tentative de reprise, le lecteur passe au
  suivant. Trois échecs d’affilée et il s’arrête, pour qu’une file injouable hors connexion ne défile
  pas d’un bloc.
- **Streaming YouTube en Wi-Fi seulement**, réglage facultatif. Il ne bloque jamais les
  téléchargements. Vérifié aux deux écrans qui lancent la lecture et dans le résolveur lui-même,
  sans quoi un titre gardé et repris depuis la bibliothèque passerait à côté du contrôle.

## Animations

Toutes suivent le réglage « Réduire les animations » : elles deviennent immobiles, jamais absentes.
Écrites en Compose dans le même langage que `LiquidNavigation` et `PlaybackPulse` — pas de fichiers
importés, donc aucun poids ajouté à l’APK.

Aurore qui dérive derrière l’en-tête de l’accueil, cartes qui arrivent en cascade et s’enfoncent sous
le doigt, lueur qui bat autour de la carte de reprise, scintillement pendant une recherche, loupe qui
respire, barres d’égaliseur sur le titre en cours, anneau de téléchargement et onde de réussite.

## Comment c’est construit

Deux moteurs, chacun pour ce qu’il fait le mieux :

- **NewPipeExtractor** pour la recherche et la lecture. Les entrées de la file gardent leur adresse `watch?v=…` ; le vrai flux n’est demandé qu’au moment où le lecteur ouvre le morceau. Une playlist de 200 titres ne coûte donc qu’une extraction par titre réellement lu, et un lien expiré est simplement redemandé.
- **yt-dlp**, avec FFmpeg, pour les téléchargements : il sait recoller une piste vidéo et une piste audio séparées, donc il atteint 1080p et au-delà, et il écrit les métadonnées et la pochette.

Les titres sont nettoyés : « Artiste - Titre (Official Video) [4K] » devient artiste « Artiste », titre « Titre ». `(Remix)`, `(Live)` et `(feat. …)` sont conservés ; une chaîne « Artiste - Topic » ou « ArtisteVEVO » donne l’artiste.

## À savoir

- **Ceci sort des conditions d’utilisation de YouTube.** L’extraction directe n’est pas autorisée par YouTube en dehors de son lecteur. C’est un usage personnel ; l’application n’est pas distribuable sur le Play Store.
- Quand YouTube change son site, l’extraction casse. Le bouton **Mettre yt-dlp à jour** répare la plupart des pannes de téléchargement sans réinstaller l’application. Une panne de recherche ou de lecture demande, elle, une nouvelle version de NewPipeExtractor, donc un nouvel APK.
- **La lecture vidéo dans l’application est limitée à 720p** : au-delà, YouTube sépare l’image et le son, et le lecteur ne lit qu’une seule piste. Les téléchargements n’ont pas cette limite.
- Un morceau écouté depuis YouTube n’est pas stocké : sans connexion, il ne se lit pas. Un morceau téléchargé est un fichier normal et se lit hors connexion.
- Les flux en direct ne sont ni lus ni téléchargés.
- L’APK grossit nettement : yt-dlp embarque Python et FFmpeg. Un APK par processeur est produit, `arm64-v8a` pour tout téléphone récent et `armeabi-v7a` pour les anciens. Pas d’APK universel : il pèserait 120 Mo pour rien.
- Le premier téléchargement prend quelques secondes de plus : l’application déballe Python et FFmpeg.
- Ouvrir un lien youtube.com directement avec Echo-All demande, sur Android 12 et plus, de l’autoriser dans **Paramètres → Applications → Echo-All → Ouvrir par défaut**. Le partage depuis l’application YouTube marche sans réglage.

## Validation

Android : 98 tests, 0 échec, 0 erreur. Vingt-deux tests ajoutés pour l’analyse des liens, le nettoyage des titres, le découpage artiste/titre, le choix de qualité et les sélecteurs yt-dlp. Build release réussi. Signature identique à la 0.14 : l’installation par-dessus conserve les données.

Deux APK sont produits : `Echo-All-0.15.apk` (arm64-v8a, 76,5 Mo) pour tout téléphone récent et `Echo-All-0.15-arm32.apk` (armeabi-v7a, 69,9 Mo) pour les anciens.

Note de construction : `ndk { abiFilters }` et `splits { abi }` faisaient double emploi. AGP refuse les deux ensemble et ne l’acceptait que parce qu’un APK universel désactive cette vérification. Seul `splits` reste.

**Aucun essai réel n’a encore eu lieu** : ni recherche, ni lecture, ni téléchargement depuis YouTube n’a été exécuté sur un téléphone. La compilation et les tests unitaires ne valident que la cohérence du code, pas l’extraction elle-même. Le contrôle Lint reste à passer.
