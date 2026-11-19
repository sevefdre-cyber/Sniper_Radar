# Analyse pondérée — CRYPTO RADAR SNIPER

Le bureau d’analyse classe les marchés sélectionnés avec un score directionnel pondéré (0–100). Les poids par défaut totalisent 100 : RSI 15, MACD 20, MA/EMA 15, SuperTrend 15, variation de prix 15, vitesse de variation 10 et fréquence des variations 10. Les réglages pourront activer/désactiver les critères et modifier leurs poids, le seuil minimal et le nombre minimal de confirmations.

Chaque calcul d’indicateur fournit une lecture normalisée de -1 (pression baissière) à +1 (pression haussière), ainsi qu’une explication facultative. Le score mesure l’accord pondéré entre les critères présents ; il ne représente pas une probabilité ni une garantie de résultat. Les éléments sont classés par score. Les signaux faibles restent en observation ; ceux qui franchissent le seuil sont candidats, et seuls ceux qui atteignent aussi le nombre de confirmations configuré passent à confirmation.

Spot et Futures peuvent être sélectionnés séparément. Les stablecoins sont filtrés à partir de l’actif de base et la liste est configurable. Ce module effectue le classement et prépare les données de rapport ; il ne passe aucun ordre. Le calcul des indicateurs, l’ingestion Binance, les préférences persistantes et les notifications seront branchés dans les étapes suivantes.
