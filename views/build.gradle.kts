import java.security.MessageDigest

dependencies {
    api(project(":kit"))
    api(project(":db"))
    api(project(":routes"))
    api(project(":assets"))
}

val templateDigest = tasks.register("templateDigest") {
    val sources = fileTree("src/main/kotlin")
    val output = layout.buildDirectory.dir("generated/digest")
    inputs.files(sources)
    outputs.dir(output)
    doLast {
        val md = MessageDigest.getInstance("SHA-256")
        sources.files.sortedBy { it.path }.forEach { md.update(it.readBytes()) }
        val file = output.get().file("campfire/views/template-digest.txt").asFile
        file.parentFile.mkdirs()
        file.writeText(md.digest().take(8).joinToString("") { "%02x".format(it) } + "\n")
    }
}

sourceSets["main"].resources.srcDir(templateDigest)
