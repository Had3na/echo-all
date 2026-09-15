# Validation — Nacre 0.3

## Exécuté

- Compilation `:app:assembleDebug` : réussie, version 0.3.0 / code 3.
- `:app:testDebugUnitTest` : 14 tests, 0 échec, 0 erreur.
- `:app:lintDebug` : réussi, 0 erreur et 64 avertissements non bloquants.
- Vérification apksigner : APK valide ; même certificat que l’APK 0.2, permettant une mise à jour en place.
- Vérification des métadonnées de sortie de l’APK.

## Portée des tests automatiques

- 3 tests de validation des liens HTTPS.
- 5 tests existants de fusion des scans et d’enveloppe de fondu : favoris/imports, refus d’accès simulé, requête en erreur simulée, masquage/doublons et limites de gains.
- 6 tests DJ : tempo sur impulsions synthétiques de 120 et 90 BPM, silence/extraits courts, tempo TAP, limites de synchronisation de vitesse et enveloppes de transition bornées.

Ces tests ne pilotent pas Android MediaStore, MediaCodec, les deux lecteurs, l’égaliseur ou l’interface. Le BPM sur de vrais morceaux n’est pas certifié par les tests sur impulsions synthétiques.

## Essais matériels non effectués

Aucun appareil connecté dans `adb devices` ; aucun émulateur configuré. Aucun test acoustique, visuel, de permissions réelles, de mise à jour réelle ni de stabilité longue durée n’a été exécuté.

## Recette recommandée sur téléphone

1. Installer par-dessus 0.2 et vérifier bibliothèque, favoris, couleur et positions.
2. Tester le scan complet et l’accès partiel Android 14+, dossiers exclus et filtre des sons courts.
3. Vérifier les pochettes et miniatures sur les médias disponibles ; contrôler le défilement d’une grosse bibliothèque.
4. Créer une playlist, renommer, ordonner, enregistrer, redémarrer et la relire ; répéter avec un album composé de photos.
5. Ajouter « Lire ensuite », déplacer/retirer un élément de file et reprendre après arrêt du service.
6. Analyser plusieurs titres locaux et une vidéo ; comparer le BPM estimé au tempo connu, puis vérifier TAP et correction manuelle.
7. Tester SYNC avec BPM proches, absents et trop éloignés ; vérifier que seul le tempo est ajusté.
8. Essayer les transitions douce, linéaire et coupure à plusieurs durées, y compris 0.
9. Préparer B, lancer A+B, déplacer le curseur, finir vers B ; fermer le studio pendant préparation et pendant mix.
10. Tester pause, casque débranché, interruption audio, seek et changement de vitesse pendant le mix.
11. Marquer entrée/sortie, lancer CUE, vérifier la sortie automatique et pendant un mix manuel.
12. Tester boucles de 4/8/16 temps, arrêt de boucle, répétition et arrêt en fin de média.
13. Tester les limites : fichier très court, fin de file, média absent, direct, source lente et erreur de décodage.
14. Vérifier égaliseur neutre, profils et bandes ; comparer à l’oreille et tester le repli si l’effet matériel est indisponible.
15. Vérifier plein écran et PiP, retour à Nacre, verrouillage, gestures volume/luminosité, SRT/VTT, signets et capture PNG.
16. Tester les gestes photo, rotation d’affichage, partage, album et diaporama.
17. Exporter une sauvegarde, changer un réglage, restaurer et vérifier les références ; tester fichier invalide et annulation du sélecteur.
18. Vérifier mode privé, effacement de l’historique et absence de nouvelles statistiques en mode privé.
19. Tester thèmes clair/sombre/système, couleur, réduction des animations, grande police, paysage et TalkBack.
20. Tester une session de musique longue avec écran verrouillé, Bluetooth et batterie ; mesurer latence et fluidité.

## Limites connues

Voir README.md et ROADMAP.md : synchronisation de phase, boucles à précision audio, collaboration réseau, NAS/TV/Auto et d’autres idées restent non implémentés. Les profils d’égalisation dépendent du matériel. Les fichiers et autorisations ne sont pas copiés par la sauvegarde.

## Environnement

SDK Android 36, Gradle 8.13, Java 21 fourni par Android Studio. Pour les commandes de l’agent, un chemin temporaire local dédié permet le repli TCP de Java afin d’éviter un problème de sockets Unix sous Windows ; ce réglage n’est pas imposé aux commandes de l’utilisateur.
