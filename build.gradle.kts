plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "io.github.anjeongkyun"
version = "0.1.0"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

repositories { mavenCentral() }

dependencies {
    api("org.testcontainers:jdbc:1.20.4")

    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("net.snowflake:snowflake-jdbc:3.19.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // snowflake-jdbc bundles Arrow, which reflects into java.nio. Without this the driver
    // fails to read results with ExceptionInInitializerError.
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}

mavenPublishing {
    publishToMavenCentral()
    // Only signs when signing credentials are configured, so local builds don't need a key.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates(group.toString(), "testcontainers-fakesnow", version.toString())

    pom {
        name.set("testcontainers-fakesnow")
        description.set("Testcontainers module for fakesnow, a local Snowflake fake reachable over JDBC.")
        inceptionYear.set("2026")
        url.set("https://github.com/anjeongkyun/testcontainers-fakesnow")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("anjeongkyun")
                name.set("Jeongkyun An")
                url.set("https://github.com/anjeongkyun")
            }
        }
        scm {
            url.set("https://github.com/anjeongkyun/testcontainers-fakesnow")
            connection.set("scm:git:https://github.com/anjeongkyun/testcontainers-fakesnow.git")
            developerConnection.set("scm:git:ssh://git@github.com/anjeongkyun/testcontainers-fakesnow.git")
        }
    }
}
