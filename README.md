# EterLib

Socle commun des plugins Eter (EterHome, EterEssential, EterMarket…). Plugin Paper sans commande : il ouvre les
connexions une seule fois et fournit les outils partagés. Document développeur, à tenir à jour avec le code.

## Ce qu'il fournit

| Élément | Rôle |
|---|---|
| `core/Sql`, `core/Cache` | Un seul pool MySQL/MariaDB et un seul pool Redis (**obligatoire** : injoignable, EterLib ne démarre pas) pour tous les plugins |
| `helper/sql/Database` | Requêtes sans SQL brut ; une instance par plugin avec **son préfixe de tables** |
| `helper/cache/RedisCache` | Clés Redis préfixées (`cache.prefix`), verrous (`setIfAbsent`, `deleteIfValue`) |
| `helper/cache/RedisMessenger` | Messages entre serveurs (pub/sub) : `publish`, `subscribe` ; un seul fil d'écoute, reconnexion automatique, messages perdus pendant une coupure |
| `helper/cache/NetworkBus` | Messages **d'un plugin** entre serveurs, sur la base de RedisMessenger : `lib.network(this, "canal", messages)`, `on(type, données -> …)` (thread principal, le serveur d'origine ignore le sien), `publish(type, json[, siÉchec])`, `notify(joueur, clé, son, variables…)` pour prévenir un joueur où qu'il soit |
| `helper/economy/Money` | Économie Vault (fournie par EterEconomy) : `Money.economy()` (null sans économie), `Money.format(montant)` |
| `core/Lang` + `helper/message/Messages` | MiniMessage, dossier `lang/` de chaque plugin, langue du client, palette commune ; **textes communs** (`command.players-only`, `error.generic`, `economy.unavailable`, `dialog.cancel`, `player.unknown`) dans les langues d'EterLib, repris par un plugin qui ne les a pas ; **préfixe commun à tous les plugins** (`language.prefix`, « Core » par défaut, `messages.prefix()`) ; `raw` + `render` pour retravailler un texte (PlaceholderAPI, lignes) ; `get(joueur, clé, balises, variables…)` pour insérer un texte déjà mis en forme (nom d'objet traduit par le client…) |
| `helper/gui` | Menus d'inventaire (`Menu`, écouteur commun, `Items`, `Sounds`) ; `Frame.draw(inventaire, couleur)` / `Frame.fill(…, cases du contenu)` : cadre commun (vitres grises, équerres de couleur aux coins : orange joueur, rouge admin) ; `Dialogs.show` : fenêtre native à deux boutons (saisie, confirmation), réponse sur le thread principal : clics annulés, double-clic protégé ; `BackButton` (`lib.backButton(commande)`) : bouton « Retour » qui lance une commande pour relier les menus (réglée dans la config de chaque plugin, `menus.<menu>.back-command`), ou « Fermer » si elle est vide |
| `helper/sidebar/SidebarOverrides` | Sidebar temporaire d'un joueur à la place de la sienne (ex : la quête suivie) : `lib.getSidebars().show(joueur, "MonPlugin", j -> contenu)`, `clear(uuid, "MonPlugin")` ; dessinée par EterTab, seul plugin qui touche au tableau de scores, contenu recalculé à chaque rafraîchissement |
| `helper/message/Durations` | Durée lisible dans la langue du joueur (« 2 j 3 h », « 30 min 5 s ») : `lib.formatDuration(joueur, secondes)` |
| `helper/task/Tasks` | Aller-retour thread principal / tâche de fond, erreurs toujours écrites dans la console |
| `module/player/PlayerDirectory` | Table `eter_players` (uuid, nom, langue, serveur actuel, première/dernière connexion) et présence réseau, `countOnline()`, `countByServer()`, `listOnline()` ; messages d'arrivée et de départ de Minecraft coupés (`vanilla-join-quit-messages: false`) : le proxy annonce l'arrivée sur le réseau |
| `module/player/OnlineNames` | Pseudos connectés (ce serveur + réseau, relus toutes les 10 s) pour la complétion avec Tab : `lib.getOnlineNames().complete(début)` |
| `module/server/ServerDirectory` | Table `eter_servers` : nom affiché de chaque serveur (`server-display-name`) et signe de vie toutes les 20 s : `lib.getServerDisplayName("survival")` → « Survie », `lib.getServers().isOnline("survival")` (hors ligne après 60 s sans nouvelles) |
| `module/server/ServerNameListener` | Canal `eter:server` avec EterTab-Velocity, à chaque arrivée de joueur : envoie `server-display-name` au proxy (pas de liste de noms à tenir sur Velocity) et reçoit le nom du serveur dans `velocity.toml` ; s'il diffère de `server-name`, **erreur claire dans la console** (sinon /home et les téléportations se trompent sans rien dire) |
| `module/tag/PlayerTags` | Étiquettes d'un joueur posées par les plugins (`lib.getPlayerTags().set(joueur, "job", texte)`) et affichées à la place de `<tag_job>` : dans la **sidebar** d'EterTab-Paper (langue du joueur) et dans la liste Tab du réseau (canal `eter:tab` vers EterTab-Velocity, gardées jusqu'à la déconnexion) |
| `module/vanish/Vanish` | Joueurs invisibles (staff) sur **tout le réseau** (`lib.getVanish().set(joueur, true)`), gardés dans Redis (`eter:vanished`) jusqu'à ce qu'on les retire : cachés sur chaque serveur aux joueurs sans `eter.vanish.see`, absents de `listOnline`, `countOnline`, `countByServer` et de la complétion des pseudos, et de la liste Tab (étiquette `vanished` pour EterTab-Velocity). Un plugin qui montre un joueur à un autre (message privé, `/find`) demande `canSee(lecteur, uuid)` |
| `module/teleport/TeleportService` | Téléportation commune : combat → cooldown → attente (bossbar) → départ, y compris vers un autre serveur ; `teleport(joueur, destination, auDépart)` pour agir seulement si le joueur part vraiment ; `teleportNow` sans aucune règle (staff) ; `connect(joueur, serveur)` : simple envoi sur un serveur (sélecteur d'un lobby) |
| `module/teleport/EterTeleportEvent` | Événement Bukkit lancé juste avant chaque départ, même vers un autre serveur (que le `PlayerTeleportEvent` de Paper ne voit pas) : position quittée et destination, ex : `/back` |

## Utilisation dans un plugin

`plugin.yml` : `depend: [EterLib]` (le jar d'EterLib doit être installé dans `plugins/`, comme Vault).

`build.gradle.kts` :
```kotlin
repositories {
    maven("https://jitpack.io")   // compile le tag GitHub demandé
    mavenLocal()                  // repli : copie publiée sur ta machine avec `gradlew publishToMavenLocal`
}
dependencies {
    compileOnly("com.github.Eternom:EterLib:1.3.0")
}
```

Nouvelle version : changer `version` dans `gradle.properties`, commiter, créer le tag Git du même nom et le pousser
(`git tag -a 1.1.0 -m "EterLib 1.1.0"` puis `git push origin 1.1.0`). JitPack compile au premier téléchargement.

```java
// En premier : vérifie la version d'EterLib (un EterLib < 1.3.0 n'a pas requireVersion, d'où le catch)
try {
    if (!EterLib.requireVersion(this, "1.3.0")) {
        return;
    }
} catch (LinkageError tooOld) {
    getLogger().severe("EterLib 1.3.0 ou plus récent est nécessaire.");
    getServer().getPluginManager().disablePlugin(this);
    return;
}
EterLib lib = EterLib.get();
Database database = lib.database("eterhome_");              // tables eterhome_*
Messages messages = lib.messages(this, "en_us", "fr_fr");  // plugins/EterHome/lang/
RedisCache redis = lib.getRedis();                          // Redis, jamais null
RedisMessenger messenger = lib.getMessenger();              // messages entre serveurs
lib.getTeleports().teleport(player, Destination.at(server, world, x, y, z, yaw, pitch, "base"));
lib.getPlayers().find("Steve");                            // n'importe quel joueur du réseau
```

## Base de données commune

Une seule base pour tout le réseau, pensée pour être lue par un futur site web :
- chaque table est préfixée par son plugin : `eter_*` (EterLib), `eterhome_*`, `eteressential_*`… ;
- l'UUID du joueur (`CHAR(36)`) relie tout ; `eter_players` est le point d'entrée ;
- colonnes en `snake_case`, dates en millisecondes (`BIGINT`), `server = NULL` = joueur hors ligne.

## Configuration

`plugins/EterLib/config.yml` : `server-name` (identique au proxy), `server-display-name` (nom montré aux joueurs), `database`, `cache` (Redis), `teleport`,
`language` (langue par défaut, préfixe des messages et palette). Les plugins n'ont plus que leurs réglages propres.
