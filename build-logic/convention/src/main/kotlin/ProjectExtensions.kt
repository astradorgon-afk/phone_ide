import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** Access to the shared version catalogue from precompiled convention plugins. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).orElseThrow {
        IllegalStateException("Version '$alias' is missing from gradle/libs.versions.toml")
    }.requiredVersion

internal fun VersionCatalog.intVersion(alias: String): Int =
    version(alias).toIntOrNull()
        ?: error("Version '$alias' in gradle/libs.versions.toml must be an integer")
