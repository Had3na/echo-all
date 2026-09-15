# Echo-All 0.9

## Plus fluide : version « release »
Les APK précédentes étaient des versions de développement (debug), plus lentes. La 0.9 est une version release : interface plus fluide, APK plus légère (15,6 Mo au lieu de 23 Mo). Même signature : installation par-dessus la 0.8.
La réduction de code (R8), qui allégerait encore l’APK, reste désactivée tant qu’une version n’a pas été testée sur téléphone : un plantage au démarrage ne pourrait pas être annulé sans désinstaller.

## Pochettes partout
- Notification, écran de verrouillage et commandes Bluetooth/voiture affichent la pochette. Une pochette choisie dans Echo-All passe avant l’image intégrée au fichier ; elle se met à jour si tu la changes pendant la lecture.
- Le mini-lecteur affiche la pochette (personnalisée, sinon celle du fichier).

## Volume égal entre les morceaux
Réglages → Égaliseur → « Volume égal entre les morceaux » (activé par défaut).
- Utilise le ReplayGain du fichier s’il existe ; sinon Echo-All mesure le volume réel (norme BS.1770, en LUFS) pendant l’écoute. Après 30 secondes, la mesure est enregistrée et appliquée dès la lecture suivante, sans changer le volume en cours de morceau.
- Niveau visé : −11 LUFS, correction limitée entre −12 et +6 dB. Non enregistré en mode privé.

## Plus de saturation
Un limiteur termine la chaîne audio : l’égaliseur poussé à +12 dB ou un morceau remonté ne sature plus. Les morceaux sans correction passent inchangés.

## Radios
Sources → « Radios » : annuaire libre Radio Browser (des dizaines de milliers de stations).
- Raccourcis : Populaires en France, Monde, Pop, Rock, Électro, Rap, Jazz, Classique, Lo-fi, Info ; recherche par nom.
- Toucher une radio l’écoute tout de suite (logo dans la notification) ; « + » l’ajoute à la bibliothèque (Musique, dossier « Radios ») avec son logo comme pochette.
- Seuls les flux HTTPS en état de marche sont proposés.

## Projet
- Code source suivi avec Git.
- Clé de signature sauvegardée dans `C:\Users\Thomas-yann\Echo-All-cles\` et utilisée par le build release (fichier `keystore.properties`, jamais versionné). Garde une seconde copie de ce dossier hors du PC.

## Validation
Compilation release, 61 tests unitaires et Lint réussis, zéro erreur Lint (78 avertissements non bloquants). La mesure de volume est vérifiée sur le signal de référence EBU (sinus 1 kHz à −23 dBFS → −23 LUFS). Requêtes radio vérifiées sur le service réel. Signature vérifiée avec apksigner : même certificat que l’APK 0.8 ; APK non débogable.
Aucun téléphone connecté : à vérifier sur appareil — pochette dans la notification et l’écran de verrouillage, écoute et ajout d’une radio, volume égal (écouter un morceau 30 s, puis le relancer), absence de saturation avec l’égaliseur au maximum.

Installer Echo-All-0.9.apk par-dessus la version précédente sans la désinstaller. Identifiant et signature conservés.
