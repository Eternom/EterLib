plugins {
    id("java-library")
    id("maven-publish")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")

    // Téléchargées au démarrage par Paper via la section `libraries` du plugin.yml
    compileOnly("com.zaxxer:HikariCP:7.0.2")
    compileOnly("redis.clients:jedis:6.2.0")
    // Pilote JDBC : compatible MySQL et MariaDB
    compileOnly("org.mariadb.jdbc:mariadb-java-client:3.5.6")
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
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
