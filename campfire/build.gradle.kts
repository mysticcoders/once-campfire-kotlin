plugins {
    application
}

dependencies {
    implementation(project(":views"))
    implementation(project(":richtext"))
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
}

application {
    mainClass.set("campfire.app.MainKt")
    applicationName = "campfire"
    applicationDefaultJvmArgs = listOf("-XX:+UseCompactObjectHeaders", "-Xmx512m", "--enable-native-access=ALL-UNNAMED")
}

distributions {
    main {
        contents {
            from(rootProject.file("native")) {
                into("native")
                include("*/*.so", "*/*.dylib")
            }
        }
    }
}
