dependencies {
    api(project(":ruby-compat"))
    api(libs.jackson.core)
    implementation("at.favre.lib:bcrypt:0.10.2")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
}
