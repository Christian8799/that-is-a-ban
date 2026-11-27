plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.22" apply false
}

group = "io.github.christian8799"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.dmulloy2.net/repository/public/")
    maven("https://repo.opencollab.dev/maven-releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.luckperms:api:5.4")
    compileOnly("net.dmulloy2:ProtocolLib:5.5.0-SNAPSHOT")
    compileOnly("io.netty:netty-all:4.1.115.Final")
    compileOnly("com.google.code.gson:gson:2.11.0")
    implementation("com.ip2location:ip2location-java:8.13.0")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

val embeddedAdapterJar = project(":flashback-nms-v26_3").tasks.named<Jar>("jar")

tasks.shadowJar {
    archiveBaseName.set("ThatsABan")
    archiveClassifier.set("")
    dependsOn(embeddedAdapterJar)
    from({ zipTree(embeddedAdapterJar.get().archiveFile.get().asFile) }) {
        include("dev/zeffut/flashbackserver/version/v26_3/**")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.jar { enabled = false }
tasks.build { dependsOn(tasks.shadowJar) }
