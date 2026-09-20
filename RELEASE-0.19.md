# Echo-All 0.19 — Correction de la recherche torrent

La recherche pouvait afficher « Recherche impossible. Vérifie les sources et ta connexion. » alors que les sources avaient répondu. Le tri comparait un nombre de seeders de type Long à la valeur de remplacement Int utilisée lorsque ce nombre était inconnu, notamment pour Internet Archive. Le mélange de résultats provoquait une ClassCastException.

La valeur de remplacement utilise désormais le même type Long. Les résultats des différentes sources peuvent être classés ensemble et la sélection automatique conserve ses critères précédents.

Validation : le cas « naruto » a reproduit la panne avec les deux API réelles avant correction, puis renvoyé 125 résultats sans erreur après correction. Aucun de ces contenus n’a été téléchargé. Un test hors ligne vérifie le classement et la sélection automatique avec des nombres de seeders connus et inconnus, dans les deux ordres d’entrée ; il échouait avant le correctif et passe après.

Installer la version 0.19 par-dessus la 0.18. La vérification de l’interface sur téléphone reste à effectuer : aucun appareil n’était connecté lors de la préparation.

Bilan final : 128 tests, 0 échec, 0 erreur ; release compilée et signatures des deux APK vérifiées. Un premier passage avait expiré dans le test de transfert magnet local ; la suite complète relancée seule a réussi sans modification du moteur ni du test. Lint : 0 erreur, 109 avertissements. Version 0.19.0, code 19, même certificat que la 0.18.
