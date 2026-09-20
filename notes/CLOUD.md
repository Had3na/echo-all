# Echo-All Notes — PC, Android et synchronisation

## Utilisation

Sur Android : installer Echo-All 0.13 par-dessus la version précédente, puis toucher **Notes** dans la barre de navigation.

Sur Windows : extraire entièrement `Echo-All-Notes-PC-0.13.zip`, puis ouvrir `Ouvrir-Notes.cmd`. Node est inclus : aucune installation de développement n’est nécessaire. Une fenêtre Edge s’ouvre, dédiée aux notes. Les notes sont enregistrées dans le profil local `AppData/Local/EchoAllNotes/Browser`. Ne supprimez pas ce dossier pour mettre l’application à jour. Fermer la fenêtre laisse le petit serveur local disponible ; `Fermer-Serveur.cmd` l’arrête après fermeture des notes.

Cette version PC concerne **l’espace Notes**. Le lecteur multimédia Android n’a pas été porté sur Windows.

- Notes texte, listes à cocher et cours manuscrits classés par matière/dossier.
- Pages lignées, quadrillées ou blanches ; stylo, pression, surligneur, gomme par trait, annuler/rétablir et zoom.
- Le doigt fait défiler ; activer « Écrire au doigt » pour dessiner sans stylet. Le filtrage du doigt limite les marques de paume, mais le comportement physique doit être testé sur le PC au stylet.
- PDF importés dès cette version : pages portrait/paysage, annotations conservées séparément de l’original et export PDF aplati. L’export transforme les pages en images : le texte du PDF exporté n’est plus sélectionnable et les liens/formulaires ne sont pas conservés. Le PDF original local reste intact.
- Les PDF chiffrés demandant un mot de passe ne sont pas pris en charge.
- Limites : 25 Mo et 500 pages par PDF, 500 pages par cahier, 4 Mo de texte/traits par note. Une erreur de quota est affichée ; ne pas quitter la note avant d’avoir exporté en cas d’échec de sauvegarde.
- « Sauvegarder » exporte les notes et les PDF dans un fichier JSON. « Restaurer » crée des copies sans remplacer les notes existantes. Sauvegarde globale limitée à 55 Mo de PDF / 80 Mo de JSON ; pour des collections plus grandes, exporter les cours individuellement et sauvegarder le serveur.
- Les sauvegardes médias historiques d’Echo-All ne contiennent pas Notes : utiliser les boutons Sauvegarder/Restaurer de l’espace Notes.

## État de la synchronisation

**Le serveur est préparé, mais aucun hébergement Internet ni compte réel n’a été créé.** Les notes fonctionnent immédiatement en local. La synchronisation Internet ne devient disponible qu’après les étapes ci-dessous.

Sur les deux appareils, saisir la même adresse HTTPS et les mêmes identifiants dans « Connexion et synchronisation ». Les notes locales sont alors associées à ce compte. Un appareil reste lié au premier compte pour éviter le mélange de cours : utiliser un autre profil de navigateur pour un autre compte. Se déconnecter ne supprime pas les données locales.

Synchronisation au lancement, au retour dans Notes, au retour du réseau et toutes les 30 secondes lorsque Notes est visible. L’application ne promet pas une synchronisation lorsque Notes est fermé. Les notes déjà téléchargées et les PDF importés restent accessibles hors connexion.

Les révisions sont vérifiées côté serveur. Si deux appareils modifient la même note, la version serveur et une **copie en conflit** de la version locale sont conservées. Comparer les deux avant d’en supprimer une. Une suppression est aussi soumise à cette vérification.

Les PDF sont vérifiés par SHA-256 et ne sont pas renvoyés à chaque trait. Les comptes sont isolés ; les mots de passe sont hachés avec scrypt ; les sessions expirent après 30 jours et sont révoquées à la déconnexion. HTTPS chiffre le transport. Ce n’est pas du chiffrement de bout en bout : l’administrateur du serveur a accès aux données stockées. Sauvegardes locales et PDF exportés ne sont pas chiffrés.

## Héberger le serveur

Prérequis : un serveur avec Docker Compose, un nom de domaine pointant vers ce serveur et les ports 80/443 accessibles. Aucun service payant n’a été souscrit, aucune règle réseau du PC n’a été modifiée.

1. Copier le dossier `notes` sur le serveur (sans `node_modules`, `data`, `dist`, ni `test-results`).
2. Copier `.env.example` vers `.env` et remplacer `notes.exemple.fr` par le domaine réel.
3. Depuis ce dossier : `docker compose up -d --build`. Caddy fournit le certificat HTTPS. Le port interne 4319 n’est pas publié sur Internet.
4. Créer un compte : `docker compose exec notes node server.mjs create-user toi@exemple.fr`. Un mot de passe aléatoire est affiché une seule fois ; le conserver dans un gestionnaire de mots de passe. Ne pas le publier dans les journaux ou captures.
5. Ouvrir `https://TON-DOMAINE` sur le PC, ou utiliser la version Windows locale, puis connecter les deux appareils. Le site peut également être installé depuis le menu Applications d’Edge.
6. Tester une note et un PDF entre les deux appareils avant d’y déposer les cours essentiels.

Le volume Docker `notes_data` contient la base SQLite (notes, PDF et comptes). Sauvegarder ce volume avec le service arrêté, ou avec une méthode SQLite cohérente incluant le journal WAL. Ne pas copier uniquement `notes.sqlite` pendant que le serveur écrit. Prévoir également la sauvegarde du volume Caddy. Les limites sont de 250 Mo de notes et 250 Mo de PDF par compte ; les fichiers orphelins et les notes supprimées ne sont pas purgés automatiquement.

Le déploiement Docker est fourni et sa configuration est validée, mais il n’a pas été mis en production ni testé avec un domaine/certificat public.

## Développement et vérification

Node 24 requis. Dans `notes` : `npm ci`, `npm run vendor`, `npm test`. Les tests navigateur utilisent Microsoft Edge installé ; ils emploient des comptes et répertoires temporaires qui sont supprimés ensuite. Le test HTTP local utilise le port 4319 : arrêter le serveur local avant le test.

`npm start` ouvre un serveur local sur 127.0.0.1:4319 (aucune exposition au réseau). Pour construire Android après un clonage : exécuter d’abord `npm ci` et `npm run vendor` dans `notes`, puis `gradlew.bat :app:assembleRelease`. Gradle copie automatiquement les assets web dans l’APK.

Sources techniques : [PDF.js](https://mozilla.github.io/pdf.js/getting_started/), [Pointer Events](https://developer.mozilla.org/en-US/docs/Web/API/Pointer_events), [contenu local Android](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content), [SQLite Node](https://nodejs.org/download/release/latest-v24.x/docs/api/sqlite.html).
