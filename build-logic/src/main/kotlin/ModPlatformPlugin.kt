@file:Suppress("unused", "DuplicatedCode")

import dev.kikugie.fletching_table.extension.FletchingTableExtension
import dev.kikugie.stonecutter.StonecutterExperimentalAPI
import dev.kikugie.stonecutter.build.StonecutterBuildExtension
import org.gradle.api.DefaultTask
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.internal.extensions.stdlib.toDefaultLowerCase
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.*
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.plugins.ide.idea.model.IdeaModel
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import javax.inject.Inject

val Project.sc: StonecutterBuildExtension
	get() = extensions.getByType<StonecutterBuildExtension>()

@OptIn(StonecutterExperimentalAPI::class)
fun Project.prop(name: String): String = (project.sc.properties.get<String>(name))

fun Project.env(variable: String): String? = providers.environmentVariable(variable).orNull

fun Project.envTrue(variable: String): Boolean = env(variable)?.toDefaultLowerCase() == "true"

fun compatibleMajorVersionRange(version: String): String {
    val major = version.substringBefore('.').toIntOrNull()
        ?: throw IllegalArgumentException("Version must start with a numeric semantic-version major: $version")
    return "[$version,${major + 1}.0.0)"
}

fun RepositoryHandler.strictMaven(
	url: String, vararg groups: String, configure: MavenArtifactRepository.() -> Unit = {}
) = exclusiveContent {
	forRepository { maven(url) { configure() } }
	filter { groups.forEach(::includeGroup) }
}

abstract class GenerateModManifestTask : DefaultTask() {
	@get:Input
	abstract val manifestPath: Property<String>

	@get:Input
	abstract val content: Property<String>

	@get:OutputDirectory
	abstract val outputDir: DirectoryProperty

	@TaskAction
	fun generate() {
		val file = outputDir.get().asFile.resolve(manifestPath.get())
		file.parentFile.mkdirs()
		file.writeText(content.get())
	}
}

abstract class ModPlatformPlugin @Inject constructor() : Plugin<Project> {
	override fun apply(project: Project) = with(project) {
		val inferredLoader = Loader.of(project.buildFile.name.substringAfter('.').replace(".gradle.kts", ""))

		val extension = extensions.create("platform", ModPlatformExtension::class.java).apply {
			loader.convention(inferredLoader.id)
			jarTask.convention(inferredLoader.jarTask)
			sourcesJarTask.convention(inferredLoader.sourcesJarTask)
		}

		listOf("org.jetbrains.kotlin.jvm", "com.google.devtools.ksp", "dev.kikugie.fletching-table").forEach {
			apply(
				plugin = it
			)
		}

		afterEvaluate {
			val ctx = Context(
				project = this,
				extension = extension,
				loader = Loader.of(extension.loader.get()),
				stonecutter = project.sc
			)
			configureProject(ctx)
		}
	}

	private fun Project.configureProject(ctx: Context) {
		listOf("java", "me.modmuss50.mod-publish-plugin", "idea").forEach { apply(plugin = it) }

		version = ctx.fullVersion
		ctx.extension.requiredJava.set(ctx.javaVersion)

		if (ctx.loader.isFabricLike) {
			ctx.extension.dependencies {
				required("java") { fabricLikeVersionRange = ">=${ctx.javaVersion.majorVersion}" }
			}
		}

		configureFletchingTable(ctx)
		registerGenerateManifestTask(ctx)
		configureJarTask(ctx)
		configureIdea()
		configureProcessResources(ctx)
		configureJava(ctx)
		configureNullnessChecks(ctx)
		configureTestTasks(ctx)
		registerBuildAndCollectTask(ctx)

		configureModPublishing(ctx)

		if (envTrue("PUB_MAVEN_ENABLE")) {
			configureMavenPublishing(ctx)
		}
	}

	private fun Project.configureJava(ctx: Context) {
		extensions.configure<JavaPluginExtension>("java") {
			withSourcesJar()
			withJavadocJar()
			sourceCompatibility = ctx.javaVersion
			targetCompatibility = ctx.javaVersion
		}
	}

	/**
	 * Nullness warnings for the mod's own code, driven by the JetBrains `@Nullable`/`@NotNull`
	 * annotations ([org.jetbrains.annotations]) this codebase already uses.
	 *
	 * Error Prone's NullAway checker is enabled in warning mode only: an unguarded dereference of
	 * an `@Nullable` value is reported by javac, but never fails a build. Only the mod's packages
	 * are analyzed; Minecraft and MineColonies are unannotated, so their nullability contracts are
	 * wrapped in annotated helpers (see `CitizenHelper.citizenData`).
	 */
	private fun Project.configureNullnessChecks(ctx: Context) {
		apply(plugin = "net.ltgt.errorprone")

		dependencies.add("errorprone", "com.google.errorprone:error_prone_core:2.42.0")
		dependencies.add("errorprone", "com.uber.nullaway:nullaway:0.14.3")

		val errorProneDependencies = configurations.create("errorProneProcessors") {
			description = "Resolvable view of the Error Prone dependencies, ordered first on the processor path."
			isCanBeConsumed = false
			isCanBeResolved = true
			extendsFrom(configurations.getByName("errorprone"))
		}
		val sourceSets = extensions.getByType<SourceSetContainer>()

		tasks.withType<JavaCompile>().configureEach {
			// Tasks outside a source set (e.g. the API-example showcase) have no annotation
			// processor path of their own; the Error Prone plugin would still pass
			// `-Xplugin:ErrorProne` and fail with "plug-in not found". They are addon code,
			// not mod code, so they are simply left unchecked.
			val sourceSet = sourceSets.find { it.getTaskName("compile", "Java") == name }
			if (sourceSet == null) {
				options.errorprone.enabled.set(false)
				return@configureEach
			}
			// Forge's Mixin annotation processor is a fat jar that embeds an ancient,
			// unrelocated copy of Guava. Error Prone needs Guava 31+, and javac resolves
			// classes from the first jar on the processor path, so the Error Prone
			// dependencies (and their Guava) have to come before the other processors.
			options.annotationProcessorPath = errorProneDependencies + sourceSet.annotationProcessorPath
			options.errorprone {
				enabled.set(true)
				// Only NullAway; the other Error Prone checks are not part of this project's contract.
				disableAllChecks.set(true)
				check("NullAway", CheckSeverity.WARN)
				option("NullAway:AnnotatedPackages", ctx.modGroup)
			}
		}
	}

	private fun Project.registerGenerateManifestTask(ctx: Context) {

		val manifestOutputDir = layout.buildDirectory.dir("generated/modManifest")
		val generateTask = tasks.register<GenerateModManifestTask>("generateModManifest") {
			manifestPath.set(ctx.loader.modManifestPath)
			content.set(ctx.loader.generateManifest(ctx))
			outputDir.set(manifestOutputDir)
		}

		the<JavaPluginExtension>().sourceSets.named("main") { resources.srcDir(manifestOutputDir) }
		tasks.named<ProcessResources>("processResources") { dependsOn(generateTask) }
		tasks.withType<Jar>().configureEach {
			if (name == ctx.loader.sourcesJarTask) {
				dependsOn(generateTask)
			}
		}
	}

	@Suppress("UnstableApiUsage")
	private fun Project.configureProcessResources(ctx: Context) {
		tasks.named<ProcessResources>("processResources") {
			dependsOn(tasks.named("stonecutterGenerate"), "kspKotlin")
			filesMatching("*.mixins.json") {
				expand("java" to "JAVA_${ctx.javaVersion.majorVersion}")
			}
			exclude(ctx.loader.excludedResources)
		}
	}

	private fun Project.configureJarTask(ctx: Context) {
		val generateTask = tasks.named("generateModManifest")
		tasks.withType<Jar>().configureEach {
			// Dedicated API artifacts own their archive name and contain no mod metadata.
			// Keep the generic mod-platform defaults on the normal mod/source/javadoc jars only.
			if (name != "apiJar" && name != "apiSourcesJar") {
				archiveBaseName.set(ctx.modId)
				dependsOn(generateTask)
			}
			// Forge 1.20.1 only loads mixin configs named in the jar manifest (mods.toml [[mixins]] is
			// NeoForge-only). reobfJar rewrites the plain jar, so the attribute must be on both.
			if (ctx.loader is Loader.Forge && (name == "jar" || name == ctx.extension.jarTask.get())) {
				manifest.attributes(ctx.loader.mixinConfigAttribute to "${ctx.modId}.mixins.json")
			}
		}
		if (ctx.loader is Loader.Forge) {
			// Dev runs pass the mixin config as a run argument, so a missing manifest entry only
			// shows up in production. Fail the build instead.
			val releaseJar = tasks.named<Jar>(ctx.extension.jarTask.get()).flatMap { it.archiveFile }
			val attribute = ctx.loader.mixinConfigAttribute
			val expected = "${ctx.modId}.mixins.json"
			val verify = tasks.register("verifyMixinManifest") {
				group = "verification"
				description = "Checks that the release jar's manifest names the mixin config (Forge loads it only from there)."
				inputs.file(releaseJar)
				doLast {
					val file = releaseJar.get().asFile
					val actual = java.util.jar.JarFile(file).use { it.manifest?.mainAttributes?.getValue(attribute) }
					if (actual != expected) {
						throw org.gradle.api.GradleException("${file.name}: manifest $attribute is '$actual', expected '$expected'; Forge would load no mixins")
					}
				}
			}
			tasks.named("check") { dependsOn(verify) }
			tasks.matching { it.name == "buildAndCollect" }.configureEach { dependsOn(verify) }
		}
	}

	private fun Project.configureIdea() {
		extensions.configure<IdeaModel>("idea") {
			module {
				isDownloadJavadoc = true
				isDownloadSources = true
			}
		}
	}

	private fun Project.configureFletchingTable(ctx: Context) {
		extensions.configure<FletchingTableExtension> {
			mixins.create("main") { mixin("default", "${ctx.modId}.mixins.json") }
			j52j.register("main") { extension("json", "**/*.json5") }
		}
	}

	private fun Project.configureTestTasks(ctx: Context) {
		val javaToolchains = extensions.getByType<JavaToolchainService>()
		tasks.withType<Test>().configureEach {
			if (ctx.javaVersion == JavaVersion.VERSION_17) {
				// Forge 1.20.1's remapped development JAR retains upstream
				// signatures. Its JUnit runtime must match the Java 17 toolchain
				// used to produce that development environment; newer runtimes
				// reject the altered signed entries during class loading.
				javaLauncher.set(javaToolchains.launcherFor {
					languageVersion.set(JavaLanguageVersion.of(17))
				})
			}
			useJUnitPlatform()
			// Restrict which class files are treated as test candidates. The
			// separate "addonApiExamples" source tree contains compile-only
			// examples that reference Forge types. Without this they would be
			// discovered as standalone test classes and trigger JDK 25's
			// JarVerifier "SHA-256 digest error" for signed Forge jars.
			include("**/*Test.class")
			include("**/*Tests.class")
			include("**/*TestCase.class")
			exclude("**/*\$*.class")
			filter {
				includeTestsMatching("*Test")
				includeTestsMatching("*Tests")
				includeTestsMatching("*TestCase")
				excludeTestsMatching("*\$*")
			}
		}
	}

	private fun Project.registerBuildAndCollectTask(ctx: Context) {
		tasks.register<Copy>("buildAndCollect") {
			from(
				tasks.named(ctx.extension.jarTask.get()),
				tasks.named(ctx.extension.sourcesJarTask.get()),
				tasks.named("javadocJar")
			)
			into(rootProject.layout.buildDirectory.file("libs/${ctx.basicVersion}"))
			dependsOn("build")
		}
	}
}
