dependencies {
    implementation(project(":domain"))
    api("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")
}
