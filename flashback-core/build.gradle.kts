plugins { java }

group = "dev.zeffut"
version = "1.2.4-embedded"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("io.netty:netty-all:4.1.115.Final")
    compileOnly("com.google.code.gson:gson:2.11.0")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}
