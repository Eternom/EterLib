plugins {
    id("java-library")
    id("maven-publish")
}

repositories {
    // PaperMC en premier : il fournit aussi les dépendances courantes, et Maven Central limite les builds JitPack (429)
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
    // VaultAPI
    // Plugins Eter (EterLib, API des autres plugins) : le jar de leur release GitHub (publiée par la CI à chaque tag)
    ivy {
        url = uri("https://github.com/Eternom/")
        patternLayout { artifact("[module]/releases/download/[revision]/[module]-[revision].[ext]") }
        metadataSources { artifact() }
        content { includeGroup("com.github.Eternom") }
    }
    // Autres dépendances publiées sur JitPack (VaultAPI...)
    maven("https://jitpack.io") { content { excludeGroup("com.github.Eternom") } }
}

dependencies {
    // Version fixe (pas de "+") : évite de lister toutes les versions de Paper à chaque build
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")

    // Téléchargées au démarrage par Paper via la section `libraries` du plugin.yml
    compileOnly("com.zaxxer:HikariCP:7.0.2")
    compileOnly("redis.clients:jedis:6.2.0")
    // Grades (préfixe, suffixe, poids) pour tous les plugins : fourni par le plugin LuckPerms s'il est installé
    compileOnly("net.luckperms:api:5.5")
    // Pilote JDBC : compatible MySQL et MariaDB
    compileOnly("org.mariadb.jdbc:mariadb-java-client:3.5.6")
    // Montants (Money) : Vault, fourni par EterEconomy sur le serveur
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") {
        exclude(group = "org.bukkit")
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

// Les autres plugins compilent contre EterLib : com.github.Eternom:EterLib:<version>.
// - n'importe où : JitPack compile le tag GitHub correspondant (voir jitpack.yml) ;
// - en local : `gradlew publishToMavenLocal` publie sous les mêmes coordonnées, pour tester avant de pousser.
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "EterLib"
            from(components["java"])
        }
    }
}

tasks {
    processResources {
        val props = mapOf("version" to version)
        // Déclarée comme entrée : sinon le cache de Gradle réutilise un plugin.yml avec l'ancienne version
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}

// Après chaque build, copie le jar dans <eterPluginsDir>/paper et y supprime l'ancienne version de ce plugin.
// eterPluginsDir se règle dans ~/.gradle/gradle.properties (propre à ta machine) : sans lui, rien n'est copié (JitPack...).
val deployPlugin by tasks.registering(Copy::class) {
    description = "Copie le jar dans le dossier de plugins local (eterPluginsDir)"
    // Variables locales à la tâche : le cache de configuration de Gradle refuse les variables du script
    val eterPluginsDir = providers.gradleProperty("eterPluginsDir")
    val enabled = eterPluginsDir.isPresent
    onlyIf { enabled }
    val jarName = tasks.jar.flatMap { it.archiveBaseName }
    from(tasks.jar)
    into(eterPluginsDir.map { "$it/paper" }.orElse(layout.buildDirectory.dir("deploy").map { it.asFile.path }))
    doFirst {
        destinationDir.listFiles { file -> file.name.startsWith(jarName.get() + "-") && file.name.endsWith(".jar") }
            ?.forEach { it.delete() }
    }
}
tasks.build { finalizedBy(deployPlugin) }
