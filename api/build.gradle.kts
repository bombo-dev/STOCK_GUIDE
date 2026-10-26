plugins {
    kotlin("plugin.spring")
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":application"))
    implementation(project(":infra"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
}

tasks.bootJar {
    archiveFileName.set("app.jar")
}
