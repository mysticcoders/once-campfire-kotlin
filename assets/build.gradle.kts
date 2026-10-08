/*
 * The asset pipeline (once-campfire-rust/crates/assets). What the Rust crate's build.rs does at
 * compile time, the `generator` source set does here: `generateAssets` runs it over reference/
 * (the Rails app), vendor/ (the gem assets on Propshaft's load path) and overrides/ (the port's
 * own frontend changes), and its output becomes this module's classpath resources under
 * campfire/assets/, which `campfire.assets.Assets` loads at run time.
 */

val generator: SourceSet = sourceSets.create("generator")

dependencies {
    api(project(":ruby-compat"))
    "generatorImplementation"(project(":ruby-compat"))
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
}

val referenceRoot: File = rootDir.resolve("reference").canonicalFile

val generateAssets = tasks.register<JavaExec>("generateAssets") {
    description = "Digests and compiles the assets like Propshaft's assets:precompile, and renders the import map."
    val output = layout.buildDirectory.dir("generated/assets")
    classpath = generator.runtimeClasspath
    mainClass.set("campfire.assets.build.GenerateKt")
    for (watched in listOf("app/assets", "app/javascript", "vendor/javascript", "public")) {
        inputs.files(fileTree(referenceRoot.resolve(watched))).withPropertyName("reference-$watched")
    }
    inputs.file(referenceRoot.resolve("config/importmap.rb")).withPropertyName("importmap")
    inputs.file(referenceRoot.resolve("config/initializers/assets.rb")).withPropertyName("assetsVersion")
    inputs.files(fileTree("vendor")).withPropertyName("vendor")
    inputs.files(fileTree("overrides")).withPropertyName("overrides")
    inputs.property("sourceDateEpoch", providers.environmentVariable("SOURCE_DATE_EPOCH").orElse(""))
    outputs.dir(output)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(projectDir.path, referenceRoot.path, output.get().asFile.path)
    })
}

sourceSets["main"].resources.srcDir(generateAssets)
