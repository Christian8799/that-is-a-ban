pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "that-s-a-ban"

include(":flashback-core", ":nms:v1_21_11", ":nms:v26_3")
