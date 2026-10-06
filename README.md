# EterLib

Socle commun des plugins Eter (EterHome, EterEssential, EterMarket…). Plugin Paper sans commande : il ouvre les
connexions une seule fois et fournit les outils partagés. Document développeur, à tenir à jour avec le code.

## Ce qu'il fournit

| Élément | Rôle |
|---|---|
| `core/Sql`, `core/Cache` | Un seul pool MySQL/MariaDB et un seul pool Redis (optionnel) pour tous les plugins |
| `helper/sql/Database` | Requêtes sans SQL brut ; une instance par plugin avec **son préfixe de tables** |
| `helper/cache/RedisCache` | Clés Redis préfixées (`cache.prefix`) |
| `core/Lang` + `helper/message/Messages` | MiniMessage, dossier `lang/` de chaque plugin, langue du client, palette commune |
| `module/player/PlayerDirectory` | Table `eter_players` (uuid, nom, langue, serveur actuel, première/dernière connexion) et présence réseau |
| `module/teleport/TeleportService` | Téléportation commune : combat → cooldown → attente (bossbar) → départ, y compris vers un autre serveur |

## Utilisation dans un plugin

`plugin.yml` : `depend: [EterLib]` (le jar d'EterLib doit être installé dans `plugins/`, comme Vault).

`build.gradle.kts` :
```kotlin
repositories {
    maven("https://jitpack.io")   // compile le tag GitHub demandé
    mavenLocal()                  // repli : copie publiée sur ta machine avec `gradlew publishToMavenLocal`
}
dependencies {
    compileOnly("com.github.nadtum-create:EterLib:1.0.0")
}
```

Nouvelle version : changer `version` dans `gradle.properties`, commiter, créer le tag Git du même nom et le pousser
(`git tag -a 1.1.0 -m "EterLib 1.1.0"` puis `git push origin 1.1.0`). JitPack compile au premier téléchargement.

```java
EterLib lib = EterLib.get();
Database database = lib.database("eterhome_");              // tables eterhome_*
Messages messages = lib.messages(this, "en_us", "fr_fr");  // plugins/EterHome/lang/
RedisCache redis = lib.getRedis();                          // null si Redis est désactivé
lib.getTeleports().teleport(player, Destination.at(server, world, x, y, z, yaw, pitch, "base"));
lib.getPlayers().find("Steve");                            // n'importe quel joueur du réseau
```

## Base de données commune

Une seule base pour tout le réseau, pensée pour être lue par un futur site web :
- chaque table est préfixée par son plugin : `eter_*` (EterLib), `eterhome_*`, `eteressential_*`… ;
- l'UUID du joueur (`CHAR(36)`) relie tout ; `eter_players` est le point d'entrée ;
- colonnes en `snake_case`, dates en millisecondes (`BIGINT`), `server = NULL` = joueur hors ligne.

## Configuration

`plugins/EterLib/config.yml` : `server-name` (identique au proxy), `database`, `cache` (Redis), `teleport`,
`language` (langue par défaut et palette). Les plugins n'ont plus que leurs réglages propres.
