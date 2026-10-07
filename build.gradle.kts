plugins {
    id("base")
    id("fabric-loom").version("1.14.10").apply(false)
}

subprojects {
    apply<JavaPlugin>()
    apply(plugin = "fabric-loom")
    repositories {
        mavenCentral()
    }
}

// This branch builds only the native Minecraft 1.21.11 module.
tasks.named("build") {
    dependsOn(":v1_21_11:build")
}

tasks.named("check") {
    dependsOn(":v1_21_11:check")
}

tasks.named("clean") {
    dependsOn(":v1_21_11:clean")
}
