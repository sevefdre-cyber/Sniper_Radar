# Radar en direct

`LiveRadarScreen` est l’écran Compose principal. Il permet de choisir Spot ou USDⓈ-M Futures, basculer le timeframe 15m/1h, actualiser les données, consulter les signaux classés et ouvrir un graphique pour une paire.

`RadarViewModel` utilise les endpoints publics en lecture seule. Pour limiter le trafic réseau, le scan est limité à 12 paires USDT et actualisé toutes les 60 secondes. Les signaux reposent sur des lectures de démonstration/simplifiées dérivées des klines : RSI, MACD, SuperTrend, impulsion de volume et fréquence ne sont pas des implémentations canoniques d’indicateurs techniques. Les scores ne sont ni probabilités ni promesses de rendement. Aucun ordre réel n’est envoyé.
