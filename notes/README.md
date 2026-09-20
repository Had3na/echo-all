# Echo-All Notes 0.14 — Même Wi-Fi, même univers

## Sur ton PC et ton téléphone

1. Sur PC, extraire toute l’archive puis ouvrir **Ouvrir-Notes.cmd**. Le relais local démarre avec l’application ; rien à installer ni à configurer comme serveur.
2. La première fois, ouvrir **Autoriser-WiFi.cmd** et accepter la demande Windows. L’exception autorise uniquement le programme Notes, le port TCP 4319 et les appareils du sous-réseau local. Elle ne désactive pas le pare-feu et ne modifie pas le profil réseau ni le routeur.
3. Installer **Echo-All 0.14** sur Android par-dessus la version précédente.
4. Sur PC : **Même Wi-Fi → Associer mon téléphone**.
5. Sur le téléphone : **Notes → Même Wi-Fi**, saisir le code affiché. On peut aussi scanner le QR avec l’appareil photo du téléphone, puis confirmer l’association dans Echo-All. Si l’appareil photo ne reconnaît pas le lien, saisir le code.

Aucun compte, mot de passe, domaine ou hébergement Internet requis. Le PC conserve une copie de synchronisation et fait le relais : il doit rester allumé, connecté au même réseau et son processus Notes doit fonctionner. Les deux espaces Notes doivent être ouverts pour synchroniser automatiquement (environ toutes les 10 secondes). Wi-Fi et Ethernet fonctionnent ensemble s’ils appartiennent au même réseau local. Le PC peut être connecté par câble.

Les notes restent consultables et modifiables hors connexion. Quand les deux appareils se retrouvent, les modifications sont envoyées. Les conflits conservent les deux versions. Les PDF ne sont pas renvoyés à chaque trait.

Le code est temporaire (5 minutes), à usage unique. Les échanges locaux sont chiffrés et authentifiés avec AES-GCM. Le code contient une clé aléatoire ; ne le partage pas avec une personne à qui tu ne veux pas donner accès aux notes. Le PC et les appareils associés gardent les données en clair dans leur stockage local : ce n’est pas une protection contre quelqu’un qui a accès à leur session ou disque.

Le bouton **Dissocier les téléphones** révoque les associations, sans supprimer les notes déjà présentes sur les téléphones. Si l’adresse IP du PC change, générer un nouveau code et refaire l’association au même PC. Un Wi-Fi invité, un VPN ou l’isolation entre appareils peut empêcher la connexion. L’association IPv6 et la découverte automatique ne sont pas incluses dans cette version.

## Le thème Echo-All

Sur Android, Notes reprend les couleurs et le mode sombre/clair des réglages d’Echo-All. Sur PC, le bouton Apparence près du logo propose le même accent rose par défaut, les couleurs de l’application et les modes sombre, clair ou système. Les cartes, commandes arrondies et le glow de sélection suivent cette palette. Les préférences visuelles restent propres à chaque appareil.

## Ce qui reste disponible

Cours au stylet, pression, gomme par trait, surligneur, annuler/rétablir, pages lignées/quadrillées/unies, notes texte, listes, classement, import de PDF et export PDF aplati. La version Windows contient l’espace Notes ; le lecteur multimédia reste sur Android.

Les PDF sont limités à 25 Mo/500 pages ; une note à 4 Mo de contenu. Les PDF exportés sont aplatis en images. Les sauvegardes de Notes sont séparées de celles des médias : utiliser **Sauvegarder** et **Restaurer** dans Notes. Les fichiers de sauvegarde ne sont pas chiffrés. Ne pas supprimer AppData/Local/EchoAllNotes lors des mises à jour ; ce dossier conserve les données du PC. Fermer-Serveur.cmd arrête le relais après avoir fermé la fenêtre Notes.

La connexion Internet avec un compte reste optionnelle dans l’interface. Son installation est décrite dans [CLOUD.md](CLOUD.md) ; elle n’est pas nécessaire pour le Wi-Fi. Un espace déjà lié à un compte cloud ne migre pas automatiquement vers un autre espace local : sauvegarder d’abord les notes.

## Validation

Tests du protocole : chiffrement/authentification, refus des paquets modifiés ou rejoués, code à usage unique, révocation et transfert notes/PDF. Parcours PC + pont Android simulé : association sans compte, modifications dans les deux sens, reconnexion et thèmes sombre/clair. Le pont réseau natif et le téléphone physique restent à tester sur appareil.

Développement : Node 24, npm ci puis npm run vendor dans notes/. npm test utilise Edge et des ports locaux de test sans fermer l’application ouverte. Pour activer le relais en développement, définir ECHO_LAN=1 et HOST=0.0.0.0 avant npm start. Ne pas exposer ce relais local sur Internet. Docker continue à utiliser le mode cloud HTTPS, sans activer les commandes d’association locale.
