import org.gradle.api.publish.maven.internal.publication.*
import java.io.*

plugins{
    `java-gradle-plugin`
    `maven-publish`
}

val arcVersion: String = providers.gradleProperty("arcVersion").get()
val mindustryVersion: String = providers.gradleProperty("mindustryVersion").get()
val javapoetVersion: String = providers.gradleProperty("javapoetVersion").get()
val kotlinVersion: String = providers.gradleProperty("kotlinVersion").get()

fun arc(module: String): String{
    return "com.github.Anuken.Arc$module:$arcVersion"
}

fun mindustry(module: String): String{
    return "com.github.Anuken.Mindustry$module:$mindustryVersion"
}

fun javapoet(): String{
    return "com.squareup:javapoet:$javapoetVersion"
}

fun kotlinPlugin(module: String): String{
    return "org.jetbrains.kotlin.$module:org.jetbrains.kotlin.$module.gradle.plugin:$kotlinVersion"
}

fun commonPom(pom: MavenPom){
    pom.apply{
        url = "https://github.com/GglLfr/EntityAnno"
        inceptionYear = "2024"

        licenses{
            license{
                name = "GPL-3.0-or-later"
                url = "https://www.gnu.org/licenses/gpl-3.0.en.html"
                distribution = "repo"
            }
        }

        issueManagement{
            system = "GitHub Issue Tracker"
            url = "https://github.com/GglLfr/EntityAnno/issues"
        }
    }
}

allprojects{
    apply(plugin = "java")
    apply(plugin = "maven-publish")

    sourceSets["main"].java.setSrcDirs(listOf(layout.projectDirectory.dir("src")))
    group = "com.github.GglLfr.EntityAnno"

    repositories{
        google()
        mavenCentral()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://oss.sonatype.org/content/repositories/releases/")
        maven("https://maven.xpdustry.com/mindustry")
        maven("https://jitpack.io")
    }

    java{
        withJavadocJar()
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach{
        options.apply{
            isIncremental = true
            isFork = false
            encoding = "UTF-8"
            compilerArgs.add("-Xlint:-options")

            compilerArgs.addAll(providers.gradleProperty("org.gradle.jvmargs").get()
                .split(Regex("\\s+"))
                .filter{it.startsWith("--add-opens")}
                .map{"--add-exports=${it.substring("--add-opens=".length)}"}
            )
        }

        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    tasks.withType<Javadoc>().configureEach{
        options{
            encoding = "UTF-8"

            val exports = providers.gradleProperty("org.gradle.jvmargs").get()
                .split(Regex("\\s+"))
                .filter{it.startsWith("--add-opens")}
                .map{"--add-exports ${it.substring("--add-opens=".length)}"}
                .reduce{accum, arg -> "$accum $arg"}

            val opts = File(temporaryDir, "exports.options")
            BufferedWriter(FileWriter(opts, Charsets.UTF_8, false)).use{it.write("-Xdoclint:none $exports")}
            optionFiles(opts)
        }
    }
}

project(":entity"){
    publishing.publications.register<MavenPublication>("maven"){
        from(components["java"])
        pom{
            name = "EntityAnno Annotation Processor"
            description = "Nearly one-to-one integration of the entity component class generator in Mindustry with " +
                    "an additional utility to properly register the entity class IDs for usage in `UnitType`s and " +
                    "persistence across save files."
            commonPom(this)
        }
    }

    sourceSets["main"].resources.setSrcDirs(listOf(layout.projectDirectory.dir("assets")))
    dependencies{
        implementation(arc(":arc-core"))
        implementation(mindustry(":core"))
        implementation(javapoet())
    }
}

project(":"){
    apply(plugin = "java-gradle-plugin")

    group = "com.github.GglLfr"

    lateinit var plugin: Provider<PluginDeclaration>
    gradlePlugin{
        isAutomatedPublishing = false

        plugin = plugins.register("entityAnno"){
            id = "com.github.GglLfr.EntityAnno"
            displayName = "EntityAnno"
            description = "Utility tools for generating Mindustry custom entity component classes."
            implementationClass = "ent.EntityAnnoPlugin"
        }
    }

    publishing.publications{
        fun applyPom(pom: MavenPom){
            pom.apply{
                name = plugin.map{it.displayName}
                description = plugin.map{it.description}
                commonPom(this)
            }
        }

        val maven = register<MavenPublication>("maven"){
            from(components["java"])
            pom{applyPom(this)}
        }

        val mavenGroupId = maven.map{it.groupId}
        val mavenArtifactId = maven.map{it.artifactId}
        val mavenVersion = maven.map{it.version}

        register<MavenPublication>("plugin") {
            (this as MavenPublicationInternal).isAlias = true
            groupId = plugin.map{it.id}.get()
            artifactId = "$groupId.gradle.plugin"

            pom{
                packaging = "pom"

                applyPom(this)
                withXml{
                    val root = asElement()
                    val doc = root.ownerDocument
                    val dependencies = root.appendChild(doc.createElement("dependencies"))
                    val dependency = dependencies.appendChild(doc.createElement("dependency"))

                    val groupId = dependency.appendChild(doc.createElement("groupId"))
                    groupId.textContent = mavenGroupId.get()

                    val artifactId = dependency.appendChild(doc.createElement("artifactId"))
                    artifactId.textContent = mavenArtifactId.get()

                    val version = dependency.appendChild(doc.createElement("version"))
                    version.textContent = mavenVersion.get()
                }
            }
        }
    }

    dependencies{
        implementation(arc(":arc-core"))
        implementation(kotlinPlugin("jvm"))
        implementation(kotlinPlugin("kapt"))
    }
}
