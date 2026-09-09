# Play SCADA + MCP + WebMCP

Un noyau de supervision réutilisable en **Play Java 3.0.11 / Java 17+**, connecté à OPC UA. Le même catalogue de variables et de commandes alimente les vues HTML, le serveur MCP et les outils WebMCP des pages.

L'intégration navigateur utilise le vrai module [`HackInvent/play-webmcp` 0.5.0](https://github.com/HackInvent/play-webmcp), téléchargé depuis sa branche Maven. Aucun code de ce module n'est recopié dans le projet.

## Démarrer la démonstration

Prérequis : JDK 17 ou 21 et sbt. Node.js 20+ n'est nécessaire que pour les tests navigateur/MCP.

```bash
./scripts/dev.sh
```

Ouvrir **http://127.0.0.1:9000**. Le simulateur OPC UA écoute sur `opc.tcp://127.0.0.1:12686/scada`. La démonstration est limitée à la machine locale par le script. Si Java 11 est la version par défaut :

```bash
SCADA_JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./scripts/dev.sh
```

La page expose la température de cuve, la pompe et une consigne réglable entre 10 et 80 °C. Les boutons, formulaires et outils WebMCP passent par les mêmes services Java. Les valeurs viennent d'une **vraie connexion OPC UA** avec le simulateur, pas de réponses HTTP simulées.

L'accès opérateur anonyme existe uniquement quand le simulateur est activé **et** que la requête HTTP provient de loopback. Un déploiement sur réseau requiert des jetons configurés. Le dessin du niveau de cuve est illustratif : aucune mesure de niveau n'est annoncée.

## Modules

| Module | Rôle |
| --- | --- |
| `modules/scada-core` | Modèle Java, catalogue, validation des commandes, état des valeurs et déduplication |
| `modules/scada-opcua` | Client Eclipse Milo 1.1.6, abonnements/reconnexion, certificats et simulateur |
| `modules/play-scada` | Services/contrôleurs Play, serveur MCP, client HTML/WebMCP, assistant OpenAI optionnel |
| `app`, `conf`, `public` | Application IHM d'exemple, remplaçable par les vues de l'intégrateur |

Le serveur MCP et le navigateur utilisent les mêmes identifiants métiers. Un identifiant de variable est associé à un `NodeId` OPC UA dans `conf/application.conf`. Les commandes désignent des variables explicitement configurées comme accessibles en écriture.

## Construire une autre IHM

Déclarer `scada.tags` et `scada.commands`, importer le module `play-scada`, monter ses contrôleurs et écrire ses vues Twirl. Le client navigateur réutilisable sait lier `data-scada-value` et `data-scada-command`, recevoir les mesures, fournir le jeton CSRF et enregistrer les outils via `play-webmcp`.

Voir [le guide d'intégration](docs/integration.md). Le projet est actuellement distribué en sources ; `sbt 'core/publishLocal' 'opcua/publishLocal' 'playScada/publishLocal'` permet une consommation locale des trois artefacts. Aucune publication Maven publique du noyau SCADA n'est annoncée.

## Serveur MCP

Endpoint : **`POST /mcp`**, transport **Streamable HTTP**, réponses JSON sans état de session.

| Outil | Usage |
| --- | --- |
| `scada_list_tags` | Catalogue des variables, types et unités |
| `scada_read_tags` | Lecture OPC UA, qualité et horodatages ; `ids` optionnel |
| `scada_list_commands` | Catalogue des commandes et de leurs limites |
| `scada_execute_command` | Commande configurée ; droit opérateur et identifiant de requête requis |

Exemple de configuration d'un client MCP acceptant un serveur HTTP :

```json
{
  "mcpServers": {
    "scada": {
      "url": "http://127.0.0.1:9000/mcp"
    }
  }
}
```

Hors démonstration locale, fournir `Authorization: Bearer <jeton>` selon la syntaxe de votre client. Les lecteurs ne reçoivent pas l'outil d'exécution dans `tools/list` et un appel direct à celui-ci est refusé côté serveur.

Les versions MCP prises en charge sont `2025-11-25`, `2025-06-18` et `2025-03-26`. L'initialisation négocie la version ; les requêtes suivantes peuvent inclure `MCP-Protocol-Version`. Le transport accepte `application/json` et `text/event-stream` dans `Accept` et utilise JSON pour les réponses. `GET /mcp` et `DELETE /mcp` retournent 405 car aucun flux SSE MCP ou état de session n'est proposé. Les notifications reçoivent 202. Aucun OAuth, serveur SSE historique, tâche différée ou ressource MCP n'est revendiqué.

Pour une commande, fournir un `requestId` stable dans les arguments, ou l'en-tête HTTP `Idempotency-Key`. Une réponse `accepted` signifie que **le serveur OPC UA a acquitté l'écriture**. Vérifier les mesures pour constater son effet sur le procédé.

## OPC UA externe

Désactiver le simulateur et configurer le serveur, les certificats, les variables et les commandes réelles. [Guide OPC UA](docs/opcua.md).

```bash
export SCADA_SIMULATOR_ENABLED=false
export SCADA_OPCUA_ENDPOINT=opc.tcp://serveur:4840/chemin
# Définir SCADA_OPERATOR_TOKEN ou SCADA_READER_TOKEN par le gestionnaire de secrets.
# Configurer la PKI et le catalogue dans application.conf.
```

Les jetons lecteur/opérateur sont des secrets de déploiement, jamais des valeurs à ajouter au dépôt. L'IHM peut ouvrir une session signée à partir d'un jeton ; la session expire après huit heures par défaut. En production, fournir `APPLICATION_SECRET`, activer `SESSION_SECURE=true` derrière HTTPS, et définir `play.filters.hosts.allowed` pour l'hôte réellement servi. L'authentification fournie peut être remplacée en liant une implémentation de `ScadaAccess` via Guice.

## Assistant OpenAI optionnel

Configurer **`OPENAI_API_KEY` et `OPENAI_MODEL`** côté serveur. L'assistant utilise l'API Responses et exécute ses appels d'outils dans le backend ; la clé n'est jamais envoyée au navigateur. Sans cette configuration, l'IHM, OPC UA, MCP et WebMCP fonctionnent normalement.

L'assistant de cette version est **en lecture seule** : les commandes restent accessibles via l'IHM et MCP avec les droits opérateur. Son catalogue d'outils et le contrôle d'exécution excluent les écritures. Les questions, définitions d'outils et résultats consultés sont transmis à OpenAI ; `store=false` est utilisé, sans prétendre désactiver toutes les règles de conservation du fournisseur. Le choix du modèle appartient au déploiement.

## Vérifications

```bash
sbt test
npm ci
npx playwright install chromium
# Avec la démo déjà démarrée :
npm run test:browser
npm run test:mcp
```

Les tests Java incluent des échanges OPC UA TCP réels, la reconnexion, le refus de certificats, les droits/limites de commandes, l'idempotence concurrente, le protocole MCP et une boucle d'outils OpenAI contre un fournisseur HTTP de test. Les cinq tests navigateur vérifient l'IHM sans API WebMCP, le client HTML minimal et le traitement d'une réponse d'écriture perdue. Le scénario WebMCP utilise une API injectée pour tester le contrat d'intégration. Le test MCP utilise le SDK client officiel.

L'appel à un vrai modèle OpenAI nécessite les secrets du déploiement ; les tests automatisés ne consomment pas de crédit OpenAI.

## Périmètre de cette première version

- Un endpoint OPC UA par runtime ; types de commande Boolean, Double, Float, Int32 et String. Le connecteur expose aussi le parcours des nœuds pour de futures fonctions d'intégration.
- Catalogue de mesures et commandes configuré en HOCON. L'historisation, les alarmes OPC UA et la haute disponibilité ne sont pas implémentées.
- Déduplication des commandes dans le processus, capacité de 4 096 identifiants et rétention minimale de 30 minutes. Elle ne survit pas à un redémarrage et ne constitue pas un journal d'audit durable. Les commandes acceptées sont journalisées côté serveur ; le journal de la page est une aide visuelle locale.
- Validation des types, plages et droits côté serveur. Les interverrouillages physiques et séquences de sûreté restent dans l'automate ; aucun automate de sécurité n'est implémenté ici.
- WebMCP dépend des capacités du navigateur/agent. L'IHM reste utilisable sans WebMCP et sans OpenAI.
