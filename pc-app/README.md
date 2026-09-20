# Echo-All PC

Sert les médias de cet ordinateur à un navigateur, et au téléphone sur le réseau local.

## Démarrer

    node server.mjs

Puis ouvrir <http://localhost:4320>.

Au premier lancement, `data/config.json` est créé avec les dossiers Musique, Vidéos et Images de
ton compte, et un jeton tiré au hasard. Ajoute ou retire des dossiers dans ce fichier, puis utilise
le bouton **Réanalyser**.

## Depuis le téléphone

Tant qu'il n'y a pas de certificat, le serveur n'écoute **que sur cet ordinateur** : le téléphone ne
peut pas s'y connecter, et c'est voulu. Pour ouvrir l'accès au réseau local, dépose un certificat et
sa clé dans `data/cert.pem` et `data/key.pem` ; le serveur passe alors en HTTPS sur toutes les
interfaces et exige le jeton de `data/config.json` dans l'en-tête `Authorization: Bearer …`.

Les requêtes venant de l'ordinateur lui-même ne demandent pas de jeton : un programme lancé ici sous
ton compte pourrait de toute façon lire ces fichiers directement.

## Ce qui est servi

- `GET /api/health` — état et dossiers analysés
- `GET /api/library?q=&kind=MUSIC|VIDEO|PHOTO` — le catalogue, 500 entrées au maximum
- `GET /api/file/<id>` — le fichier, avec requêtes Range (nécessaire pour se déplacer dans un média)
- `POST /api/rescan` — relance l'analyse

## Tests

    node --test

## Limites

L'analyse lit les noms de fichiers, pas les métadonnées internes : l'artiste et le titre sont devinés
depuis le nom. Aucune pochette. La bibliothèque du téléphone n'est pas encore visible ici : c'est
l'étape suivante.

## Application Windows

Depuis la racine : npm ci --prefix pc-app puis npm run dist --prefix pc-app.
Installateur dans pc-app/dist. npm start --prefix pc-app lance la version de développement.
Configuration des comptes : ../FIREBASE-SETUP.md.
