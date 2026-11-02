plugins {
	`kotlin-dsl`
	kotlin("plugin.serialization") version embeddedKotlinVersion
}

gradlePlugin {
	plugins {
		register("modPlatform") {
			id = "mod-platform"
			implementationClass = "ModPlatformPlugin"
		}
	}
}

repositories {
	mavenCentral()
	gradlePluginPortal()
	maven("https://maven.fabricmc.net/") { name = "Fabric" }
	maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
	maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
	maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
	maven("https://jitpack.io") { name = "Jitpack" }
}

dependencies {
	implementation(libs.kikugie.postprocess)
	implementation(libs.kikugie.stonecutter)
	implementation(libs.mod.publish.plugin)
	implementation(libs.foojay.resolver)
	implementation(libs.fletching.table)
	implementation(libs.vanniktech.maven.publish)

	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
	implementation("net.peanuuutz.tomlkt:tomlkt:0.4.0")
	implementation("net.ltgt.gradle:gradle-errorprone-plugin:5.2.0")
}