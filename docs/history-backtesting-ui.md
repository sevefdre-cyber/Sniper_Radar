# Interface Historique et Backtesting

L’onglet Historique permet de sélectionner Spot/Futures, une paire et un intervalle, télécharger jusqu’à 10 000 bougies publiques Binance sur les 90 derniers jours, puis les conserver dans SQLite. « Charger archive » relit localement les bougies précédemment enregistrées. Le backtest fonctionne hors ligne avec le moteur historique existant et affiche rendement net, drawdown, taux gagnant et transactions.

Le modèle de signal livré dans l’écran est un exemple momentum à seuil fixe, non une stratégie recommandée. Les frais et un slippage simple sont inclus; le funding Futures n’est pas encore modélisé. Aucun ordre réel n’est envoyé. Les résultats passés ne garantissent pas les résultats futurs.
