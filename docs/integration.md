# Intégrer une IHM Play Java

## Dépendance et configuration

Le dépôt contient une application de démonstration et trois bibliothèques réutilisables. Pour les publier dans votre dépôt Ivy local :

```bash
sbt 'core/publishLocal' 'opcua/publishLocal' 'playScada/publishLocal'
```

Dans une application **PlayJava 3.x**, Java 17+, Scala 2.13 :

```scala
resolvers += "HackInvent play-webmcp" at "https://raw.githubusercontent.com/HackInvent/play-webmcp/maven"
libraryDependencies += "io.github.hackinvent" %% "play-scada" % "0.1.0-SNAPSHOT"
```

`play-scada` apporte `scada-core`, `scada-opcua` et `io.github.alexusel:play-webmcp_2.13:0.5.0` transitivement. Le consommateur utilise ses propres vues, contrôleurs métier et styles. Le code applicatif et les modules SCADA sont en Java ; `build.sbt` et les templates Twirl utilisent la syntaxe habituelle de Play.

Dans `application.conf`, charger les valeurs par défaut du module :

```hocon
include "scada-reference.conf"
```

Définir ensuite le serveur OPC UA, la PKI et les listes `scada.tags` / `scada.commands`. Copier le catalogue de la démo uniquement pour le simulateur ; les identifiants et plages d'un équipement réel appartiennent à votre projet.

## Routes et catalogue d'outils

Monter les routes `/api/scada/*` et `/mcp` présentées dans [`conf/routes`](../conf/routes). Les mutations navigateur du module utilisent une action CSRF explicite : elle exige un jeton Play valide même sans en-tête `Cookie` ou `Authorization`, y compris en mode démonstration local. Le client transmet le jeton dans `Csrf-Token`, accompagné du cookie de session. Garder les protections CSRF de Play actives. Le contrôleur de votre page peut injecter `io.hackinvent.scada.play.ScadaRuntime` et transmettre `runtime.browserTools()` à sa vue. Ajouter `@AddCSRFToken` sur l’action GET.

Placer le modificateur `+ nocsrf` uniquement devant la route `POST /mcp`, comme dans le fichier de routes fourni : les clients MCP distants utilisent leur jeton Bearer sans jeton CSRF navigateur. Le contrôleur MCP conserve ses vérifications d’origine et d’authentification. Ne pas appliquer ce modificateur aux routes de commande, de session ou d’assistant.

```java
@AddCSRFToken
public Result index(Http.Request request) {
    return ok(views.html.index.render(runtime.browserTools(), request.asScala()));
}
```

Les composants sont injectés par Guice via leurs constructeurs Java. `ScadaRuntime` démarre la connexion et l'arrête avec le cycle de vie Play. Pour intégrer l'identité de votre application, fournir une sous-classe de `ScadaAccess` et la lier via Guice ; les contrôleurs utilisent ses méthodes `canRead`, `isOperator` et `isAllowedOrigin` pour chaque accès. Le constructeur protégé `super(config, false)` permet de remplacer l’authentification par jetons sans configurer de jetons factices. Redéfinir également `authenticate` si vous conservez la route de création de session.

## Vue HTML minimale

Une vue peut se limiter aux éléments HTML et à l'initialisation du client. Exemple de template Twirl :

```html
@(tools: java.util.List[playwebmcp.Tool])(implicit request: play.api.mvc.RequestHeader)
@import playwebmcp.javadsl.WebMcp
@import scala.jdk.CollectionConverters._

<div hidden>@helper.CSRF.formField</div>
<output data-scada-value="tank.temperature" data-scada-unit="°C">—</output>
<span data-scada-quality="tank.temperature"></span>
<time data-scada-timestamp="tank.temperature"></time>
<button type="button" data-scada-command="pump.start">Démarrer</button>
<button type="button" data-scada-command="pump.stop">Arrêter</button>

<input id="setpoint" type="number" min="10" max="80" value="24">
<button type="button" data-scada-command="tank.setpoint"
        data-scada-input="#setpoint">Appliquer la consigne</button>

@for(tool <- tools.asScala) { @WebMcp.tool(tool) }
<script defer src="@controllers.routes.Assets.versioned("lib/play-webmcp/play-webmcp.global.js")"></script>
<script defer src="@controllers.routes.Assets.versioned("lib/play-scada/play-scada.js")"></script>
<script defer src="@controllers.routes.Assets.versioned("javascripts/my-hmi.js")"></script>
```

Dans `my-hmi.js` :

```javascript
const client = PlayScada.createClient({
  baseUrl: '/api/scada/',
  locale: 'fr',
  onError(error) { console.error(error.message); }
});
client.start({ root: document }).catch(error => console.error(error.message));
window.addEventListener('pagehide', event => {
  if (!event.persisted) client.dispose().catch(error => console.error(error.message));
});
```

Adapter `baseUrl` si l'application est montée sous un préfixe ; cette URL doit rester sur la même origine que la page. Le test `event.persisted` conserve le client quand le navigateur met la page en cache pour la navigation précédent/suivant. Le client lit le champ CSRF produit par Play, met à jour les éléments liés, reçoit le flux des mesures et enregistre les outils avec le runtime `play-webmcp`. L'absence de WebMCP ne désactive pas l'IHM. Fournir un callback `confirmCommand` pour personnaliser la confirmation visuelle ; les droits et la validation définitive restent côté serveur.

## API du client navigateur

| Méthode | Usage |
| --- | --- |
| `start({root, bindCommands})` | Charger le catalogue/les mesures, démarrer l'acquisition et WebMCP |
| `refreshCatalog()` / `refreshSnapshot()` | Actualiser les données de la page |
| `listTags(args, context)` / `readTags({ids}, context)` | Outils de consultation |
| `listCommands(args, context)` | Décrire les commandes disponibles |
| `executeCommand({commandId, value, requestId}, context)` | Confirmer puis envoyer une commande sans retry automatique |
| `openSession(token)` / `closeSession()` | Ouvrir/fermer une session serveur |
| `askAssistant(message)` | Poser une question à l'assistant optionnel |
| `registerTools({root})` / `bind(root)` | Intégration de composants HTML |
| `dispose()` | Arrêter les flux et désenregistrer les outils de ce client |

Les callbacks `onCatalog`, `onSnapshot`, `onCommand` et `onError` permettent une présentation personnalisée. Le paramètre `context.signal` des outils WebMCP est propagé aux requêtes. Une interruption réseau après une écriture peut laisser son résultat inconnu ; le client relit l'état mais ne renvoie jamais automatiquement la commande.

## Données et commandes

Chaque mesure conserve `value`, `quality`, `statusCode`, `sourceTimestamp`, `serverTimestamp` et `receivedAt`. Le snapshot peut remplacer la qualité d'affichage par `STALE` quand la connexion est perdue ou l'acquisition trop ancienne ; le code OPC UA et les horodatages d'origine sont conservés.

Les commandes sont définies par un identifiant stable, une variable cible et soit une valeur fixe, soit un argument typé et ses bornes. Le `ScadaEngine` refuse les variables inconnues/non inscriptibles, les types incorrects, les nombres non finis, les dépassements de plage, les lecteurs et les réutilisations incohérentes d'un identifiant de requête.

Le navigateur et MCP passent par ce même moteur. L'API OpenAI reçoit uniquement les outils de lecture dans cette version. Une protection d'IHM ou une annotation WebMCP n'accorde jamais de permission supplémentaire sur le serveur.

L’option `scada.openai.timeout` borne la requête complète de l’assistant : appels au modèle, tours d’outils et lectures OPC UA. Un dépassement termine la requête avec `assistant_timeout` (HTTP 504) ; l’arrêt de l’application annule les requêtes en cours.
