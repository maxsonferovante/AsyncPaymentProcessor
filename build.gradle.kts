plugins {
    java
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.graalvm.buildtools.native") version "0.10.6"
}

group = "com.maal"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-redis") {
        exclude(group = "io.netty")
    }
    implementation("org.springframework.boot:spring-boot-starter-web") {
        exclude(group = "io.netty")
    }
    implementation("io.netty:netty-all:4.2.3.Final")

    // Jackson para serialização JSON (ainda necessário para Redis)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    
    // Lombok para reduzir boilerplate
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    
    // Testes
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}


// Configuração para eliminar avisos do JDK 24+ relacionados ao Netty
tasks.withType<JavaExec> {
    jvmArgs(
        "--sun-misc-unsafe-memory-access=allow",
        "--enable-native-access=ALL-UNNAMED"
    )
}

// Configuração para bootRun
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs(
        "--sun-misc-unsafe-memory-access=allow",
        "--enable-native-access=ALL-UNNAMED",
        // logs
        "-Dlogging.level.org.springframework.web=DEBUG",
        "-Dlogging.level.org.springframework.web.servlet.DispatcherServlet=DEBUG",
        "-Dlogging.level.org.springframework.web.filter.CommonsRequestLoggingFilter=DEBUG",
        "-Dlogging.level.org.springframework.web.filter.CommonsRequestLoggingFilter=DEBUG",
    )
}

// Configuração para testes
tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs(
        "--sun-misc-unsafe-memory-access=allow",
        "--enable-native-access=ALL-UNNAMED"
    )
}
