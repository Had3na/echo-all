# Activer les comptes Echo-All avec Firebase

GitHub contient le code et les versions téléchargeables. Firebase héberge les comptes,
conversations et fichiers. GitHub Pages ne remplace pas ces services.

## 1. Projet et connexion Google

Dans https://console.firebase.google.com/, créer un projet puis activer :
- Authentication → Google (configurer l'adresse d'assistance).
- Cloud Firestore, base (default), en mode production.
- Cloud Storage. Vérifier les conditions de facturation affichées avant activation.

Enregistrer l'application Android fr.nacre.media, ajouter les empreintes SHA-1 et SHA-256 de
la clé de signature de distribution. Télécharger ensuite google-services.json dans
app/google-services.json. Il doit comporter le client OAuth Web de type 3.

Enregistrer une application Web et copier ses identifiants publics dans
social-web/firebase-config.json en suivant firebase-config.example.json.
Ces configurations ne sont pas des clés administrateur, mais sont ignorées par Git pour
éviter de publier accidentellement une configuration personnelle.

Dans Authentication → Settings → Authorized domains, autoriser 127.0.0.1, localhost et
les domaines Firebase Hosting utilisés. Sur Windows, Google se connecte dans le navigateur
système puis renvoie le résultat à l'application par une boucle locale temporaire.
Le jeton ne figure jamais dans l'URL : il est envoyé par POST. Firebase vérifie ensuite le
jeton et conserve la session dans le profil de l'application.

## 2. Déployer les règles et l'interface Web

Depuis la racine du dépôt :

    npm ci --prefix firebase
    firebase/node_modules/.bin/firebase login
    firebase/node_modules/.bin/firebase deploy --project TON_PROJECT_ID --only firestore:rules,storage,hosting

Autoriser l'intégration Firestore/Storage lorsque Firebase la demande. Les fichiers ne sont
lisibles que par les membres d'une conversation acceptée. Ne jamais remplacer les règles
par une autorisation générale.

Pour les téléchargements PC/Web, configurer CORS sur le bucket : copier
firebase/storage-cors.example.json, remplacer YOUR_PROJECT et appliquer avec :

    gcloud storage buckets update gs://TON_BUCKET --cors-file=TON_FICHIER_CORS.json

Le serveur PC garde le port 4320 entre les lancements, afin de conserver l'origine du
navigateur et la session. Si ce port est occupé, fermer l'autre serveur Echo-All avant de
relancer. Ne pas ouvrir ce port sur Internet.

## 3. Construire

    npm ci --prefix notes
    npm run vendor --prefix notes
    ./gradlew.bat :app:assembleRelease
    npm ci --prefix pc-app
    npm run dist --prefix pc-app

La release Android utilise keystore.properties et la clé de signature d'origine, déjà prévus
par le projet. Ne jamais partager leurs mots de passe dans une conversation ni dans Git.

## 4. GitHub Releases et mises à jour

Dans les secrets GitHub Actions du dépôt, configurer :
- ANDROID_KEYSTORE_BASE64, ANDROID_STORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD
- FIREBASE_ANDROID_JSON : contenu de google-services.json
- FIREBASE_WEB_JSON : contenu de firebase-config.json

Le workflow manuel Build signed release construit les deux plateformes et crée un brouillon
de release v0.24.0 avec les APK signés, l'installateur Windows et version.json.
Tester puis publier ce brouillon pour rendre la détection opérationnelle :
https://github.com/Had3na/echo-all/releases/latest/download/version.json

Les fichiers de release doivent être accessibles aux utilisateurs sans jeton GitHub.
Si le dépôt est privé, utiliser un dépôt de releases public ou un hébergement public et adapter
les adresses. Ne jamais intégrer de jeton GitHub dans l'application.

Pour chaque prochaine version, incrémenter versionCode/versionName Android et la version de
pc-app/package.json ensemble. Utiliser une nouvelle balise de release.

## 5. Essai avant diffusion

1. Installer les versions configurées sur Android et Windows.
2. Connecter le même compte Google sur les deux, fermer puis rouvrir pour vérifier la session.
3. Connecter un second compte et lui envoyer une invitation avec son identifiant.
4. Accepter ; envoyer un message et un fichier dans les deux sens.
5. Bloquer la conversation et vérifier le refus des lectures et envois.
6. Tester les mises à jour avec une release publiée et la même signature Android.

Une session peut expirer ou être révoquée par Google et nécessiter une reconnexion.
Se déconnecter sur un appareil ne déconnecte pas l'autre.

Documentation :
- https://firebase.google.com/docs/auth/android/google-signin
- https://firebase.google.com/docs/auth/web/auth-state-persistence
- https://firebase.google.com/docs/storage/security
