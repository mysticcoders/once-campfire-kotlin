dependencies {
    api(project(":rails-compat"))
    api(project(":routes"))
    api(platform(libs.netty.bom))
    api(libs.netty.codec.http)
    api("io.netty:netty-transport-native-epoll::linux-x86_64")
    api("io.netty:netty-transport-native-epoll::linux-aarch_64")
    api("io.netty:netty-transport-native-kqueue::osx-aarch_64")
    implementation("com.github.luben:zstd-jni:1.5.7-6")
    compileOnly(libs.graal.svm)
}

/**
 * The reference's `public/<status>.html` error pages, copied onto the classpath at
 * `campfire/kit/public/` for `ErrorPages.PUBLIC` (the Rust app embeds the same files).
 */
val publicErrorPages = tasks.register<Sync>("publicErrorPages") {
    from(rootProject.file("reference/public")) {
        include("404.html", "422.html", "500.html", "502.html")
    }
    into(layout.buildDirectory.dir("generated/public/campfire/kit/public"))
}

sourceSets["main"].resources.srcDir(publicErrorPages.map { layout.buildDirectory.dir("generated/public").get() })

tasks.withType<Test> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
