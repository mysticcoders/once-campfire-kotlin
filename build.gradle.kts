plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }

    dependencies {
        "testImplementation"(kotlin("test"))
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        workingDir = rootDir
        systemProperty("campfire.vectors", rootDir.resolve("vectors").path)
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        val os = System.getProperty("os.name").lowercase()
        val arch = if (System.getProperty("os.arch") in setOf("amd64", "x86_64")) "x86_64" else "aarch64"
        val platform = (if ("mac" in os) "macos-" else "linux-") + arch
        val library = if ("mac" in os) "libcampfire_sqlite3.dylib" else "libcampfire_sqlite3.so"
        environment("CAMPFIRE_SQLITE_LIB", rootDir.resolve("native/$platform/$library").path)
    }
}
