# Historique local, backtesting et optimisation

Les klines OHLCV historiques peuvent être téléchargées par pages via l’API publique Binance, sans clé ni accès au compte. Le téléchargeur borne le nombre de bougies, avance son curseur et permet de sauvegarder chaque lot avec `LocalCandleStore`, une archive SQLite privée à l’application. Les données restent sur l’appareil et peuvent être relues pour les graphiques ou les tests.

`HistoricalBacktestEngine` évalue un modèle fourni par l’application sur les seules bougies déjà clôturées, entre au prix d’ouverture de la bougie suivante et simule frais, slippage, stop-loss, cible et durée maximale. Si stop et cible sont tous deux touchés dans une bougie, le stop est supposé touché en premier (hypothèse prudente). Le moteur ne passe aucun ordre réel.

`HistoricalStrategyOptimizer` explore une grille configurable de paramètres de stratégie, score minimum, stop-loss, objectif et horizon. La taille de grille est contrôlée par `maxCandidates`. Les résultats rapportent séparément entraînement et validation chronologique, et le classement pénalise le drawdown de validation pour réduire le risque de choisir une combinaison trop ajustée à l’historique. Toute performance passée simulée ne prédit pas les résultats futurs ; tenir compte des frais, de la qualité des données et du surajustement.
