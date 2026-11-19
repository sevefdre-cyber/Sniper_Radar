# Données publiques Binance

L’application récupère les données de marché publiques sans clé API et sans accès au compte :

- Spot : `GET https://api.binance.com/api/v3/klines` et `/api/v3/exchangeInfo`.
- USDⓈ-M Futures : `GET https://fapi.binance.com/fapi/v1/klines` et `/fapi/v1/exchangeInfo`.

`BinancePublicMarketData` convertit les klines OHLCV en bougies consommables par le graphique, valide symbole/unité de temps/limite et filtre les symboles non négociés, les contrats Futures non perpétuels, ainsi que les stablecoins de base configurés. La limite de bougies est plafonnée à 1000. Les réponses d’erreur incluent le délai `Retry-After` lorsqu’il est fourni ; l’appelant doit ralentir ou attendre au lieu de relancer en boucle. Binance peut modifier les limites et la disponibilité des domaines : respecter les en-têtes de réponse et consulter la documentation officielle actuelle.

Cet accès est en lecture seule. La classe n’expose aucun endpoint d’ordre, de compte ou de retrait. Les valeurs d’entrée, cible et stop-loss du graphique restent des repères locaux, sans exécution automatique.
