plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.22" apply false
}

group = "io.github.christian8799"
version = "1.2.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.dmulloy2.net/repository/public/")
}

dependencies {
    implementation(project(":flashback-core"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.luckperms:api:5.4")
    compileOnly("net.dmulloy2:ProtocolLib:5.5.0-SNAPSHOT")
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

evaluationDependsOn(":flashback-core")
evaluationDependsOn(":nms:v1_21_11")
val flashbackCoreJar = project(":flashback-core").tasks.named<Jar>("jar")
val flashbackAdapterJar = project(":nms:v1_21_11").tasks.named<Jar>("reobfJar")

tasks.jar {
    archiveBaseName.set("ThatsABan")
    dependsOn(flashbackCoreJar, flashbackAdapterJar)
    from({ zipTree(flashbackCoreJar.get().archiveFile.get().asFile) })
    from(zipTree(flashbackAdapterJar.map { it.outputs.files.singleFile })) {
        include("dev/zeffut/flashbackserver/version/v1_21_11/**")
    }
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.startsWith("ip2location-java-") }
            .map { zipTree(it) }
    })
    from("licenses/FlashbackServer-MIT-LICENSE") {
        into("META-INF/licenses")
        rename { "FlashbackServer-MIT-LICENSE.txt" }
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.build {
    dependsOn(tasks.jar)
}
