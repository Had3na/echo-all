# Echo-All 0.24 — Ordinateur, comptes et amis

Cette branche ajoute une application Windows installable, une vérification GitHub au démarrage
Android/PC, et les comptes Google partagés entre appareils via Firebase.

## État de livraison

- Android : APK de développement compilables. Une mise à jour de l'application déjà installée
  nécessite une release signée avec sa clé d'origine.
- Windows : installateur NSIS x64 ; bibliothèque musicale, vidéos et photos locales, comptes et
  conversations. Les fonctions YouTube, torrents et studio restent spécifiques à Android.
- Firebase : code et règles prêts, mais aucun projet de production n'a encore été configuré.
  La connexion devient utilisable après cette configuration.
- Chaque appareil garde sa propre session. Une première connexion Google sur chacun est
  nécessaire ; les amis et conversations sont ensuite communs.
- Mises à jour : détection une fois par jour au retour dans l'application, puis téléchargement
  avec confirmation de l'utilisateur. Pas d'installation silencieuse.
- Amis : invitations par identifiant, acceptation, refus/blocage, messages texte et pièces jointes
  image/audio/vidéo de 25 Mo maximum. 100 derniers messages affichés.
- Aucun chiffrement de bout en bout, notifications push ou synchronisation automatique de toute
  la bibliothèque locale dans cette première version.

## Activation Firebase

Voir [le guide](FIREBASE-SETUP.md). Tester deux comptes réels sur Android et Windows
avant de présenter cette version comme opérationnelle.

## Validation

Compilation Android, assemblage des APK de développement et tests unitaires Android.
Tests de bibliothèque PC et construction de l'installateur Windows.
Six tests Firestore/Storage avec les émulateurs : invitations, confidentialité, expéditeur,
profils, blocage et fichiers.

Le conflit entre NewPipe (protobuf 4.x) et Firebase est traité en conservant les types Firebase,
sauf DescriptorProtos déjà fourni par protobuf. Les appels Google réels restent à tester après
configuration des clients OAuth.
