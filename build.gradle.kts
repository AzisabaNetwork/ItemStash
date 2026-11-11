plugins {
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
    `maven-publish`
}

group = "net.azisaba"
version = "3.0.2+1.21.11"

repositories {
    mavenCentral()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
    maven { url = uri("https://repo.azisaba.net/repository/maven-public/") }
    mavenLocal()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.zaxxer:HikariCP:4.0.3")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.0.6")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:24.0.1")
    compileOnly("net.azisaba:LifeCore:6.9.9") { isTransitive = false }
    compileOnly("xyz.acrylicstyle:StorageBox:1.6.4+1.21.11") { isTransitive = false }
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.8.1")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Generate real default methods in interfaces so Java callers can use ItemStash's default methods
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

java {
    withJavadocJar()
    withSourcesJar()
}

tasks {
    processResources {
        filteringCharset = "UTF-8"
        from(sourceSets.main.get().resources.srcDirs) {
            include("**")

            val tokenReplacementMap = mapOf(
                "version" to project.version,
                "name" to project.rootProject.name,
            )

            filter<org.apache.tools.ant.filters.ReplaceTokens>("tokens" to tokenReplacementMap)
        }

        duplicatesStrategy = DuplicatesStrategy.INCLUDE

        from(projectDir) { include("LICENSE") }
    }

    compileJava {
        options.encoding = "UTF-8"
    }

    javadoc {
        options.encoding = "UTF-8"
    }

    shadowJar {
        relocate("kotlin", "net.azisaba.itemstash.libs.kotlin")
        relocate("org.mariadb.jdbc", "net.azisaba.itemstash.libs.org.mariadb.jdbc")
        relocate("com.zaxxer.hikari", "net.azisaba.itemstash.libs.com.zaxxer.hikari")
        exclude("org/jetbrains/annotations/**", "org/intellij/lang/annotations/**")
    }

    build {
        dependsOn(shadowJar)
    }
}

publishing {
    repositories {
        maven {
            name = "repo"
            credentials(PasswordCredentials::class)
            url = uri(
                if (project.version.toString().endsWith("-SNAPSHOT"))
                    "https://maven.azisaba.net/snapshots"
                else
                    "https://maven.azisaba.net/releases"
            )
        }
    }

    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
