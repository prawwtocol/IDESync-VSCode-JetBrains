plugins {
    id("org.jetbrains.intellij") version "1.17.2"
    kotlin("jvm") version "1.9.22"
    id("com.github.ben-manes.versions") version "0.51.0"
}

group = "com.vscode.jetbrainssync"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
version = file("version.properties").readLines().first().substringAfter("=").trim()

repositories {
    mavenCentral()
    maven { url = uri("https://cache-redirector.jetbrains.com/intellij-dependencies") }
}

// Configure Gradle IntelliJ Plugin
intellij {
    version.set("2023.3")
    downloadSources.set(true)
    instrumentCode.set(false) // Disable code instrumentation temporarily
}

dependencies {
    implementation("org.java-websocket:Java-WebSocket:1.5.4")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.jetbrains.rd:rd-core:2023.3.2")
    implementation("com.jetbrains.rd:rd-framework:2023.3.2")
}

sourceSets {
    main {
        kotlin {
            srcDirs("src/main/kotlin")
        }
        resources {
            srcDirs("src/main/resources")
        }
    }
}

tasks {
    buildSearchableOptions {
        enabled = false
    }
    
    patchPluginXml {
        version.set("${project.version}")
        sinceBuild.set("233")
        untilBuild.set(provider{null})
    }

    register("syncVersionToVSCode") {
        group = "build"
        description = "Synchronize version from version.properties to VSCode extension package.json"
        
        doLast {
            val packageJsonFile = file("../vscode-extension/package.json")
            if (packageJsonFile.exists()) {
                val packageJson = packageJsonFile.readText()
                val updatedPackageJson = packageJson.replace(
                    """"version":\s*"[^"]*"""".toRegex(),
                    """"version": "${project.version}""""
                )
                packageJsonFile.writeText(updatedPackageJson)
                println("VSCode Extension version synchronized to: ${project.version}")
            }
        }
    }

    buildPlugin {
        dependsOn("syncVersionToVSCode")
        archiveBaseName.set("vscode-jetbrains-sync")
        archiveVersion.set(project.version.toString())
    }

    runIde {
        // Configure JVM arguments for running the plugin
        jvmArgs("-Xmx2g")
    }
    
    processResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    
    prepareSandbox {
        doLast {
            copy {
                from("${project.projectDir}/src/main/resources")
                into("${intellij.sandboxDir.get()}/plugins/${project.name}/lib")
                include("**/*")
            }
        }
    }
    
    compileKotlin {
        kotlinOptions {
            jvmTarget = "17"
            apiVersion = "1.8"
            languageVersion = "1.8"
            freeCompilerArgs = listOf("-Xjvm-default=all")
        }
    }

    withType<JavaCompile>().configureEach {
        options.release.set(17)
    }
} 