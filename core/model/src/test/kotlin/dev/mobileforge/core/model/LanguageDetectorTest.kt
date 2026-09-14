package dev.mobileforge.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LanguageDetectorTest {

    @Test
    fun `detects php`() {
        assertThat(LanguageDetector.detect("Kernel.php")).isEqualTo("php")
    }

    @Test
    fun `detects blade templates ahead of php`() {
        // welcome.blade.php ends in .php, so a naive extension lookup gets this wrong.
        // Blade is a distinct language mode and must win.
        assertThat(LanguageDetector.detect("welcome.blade.php")).isEqualTo("blade")
    }

    @Test
    fun `detects artisan as php despite having no extension`() {
        assertThat(LanguageDetector.detect("artisan")).isEqualTo("php")
    }

    @Test
    fun `detects typescript and tsx`() {
        assertThat(LanguageDetector.detect("app.ts")).isEqualTo("typescript")
        assertThat(LanguageDetector.detect("App.tsx")).isEqualTo("typescript")
    }

    @Test
    fun `detects dotenv files as ini`() {
        assertThat(LanguageDetector.detect(".env")).isEqualTo("ini")
        assertThat(LanguageDetector.detect(".env.example")).isEqualTo("ini")
    }

    @Test
    fun `detects composer and package manifests as json`() {
        assertThat(LanguageDetector.detect("composer.json")).isEqualTo("json")
        assertThat(LanguageDetector.detect("package-lock.json")).isEqualTo("json")
    }

    @Test
    fun `detects dockerfile by whole filename`() {
        assertThat(LanguageDetector.detect("Dockerfile")).isEqualTo("dockerfile")
    }

    @Test
    fun `is case insensitive`() {
        assertThat(LanguageDetector.detect("INDEX.PHP")).isEqualTo("php")
    }

    @Test
    fun `falls back to plaintext for unknown extensions`() {
        assertThat(LanguageDetector.detect("archive.zzz")).isEqualTo("plaintext")
    }

    @Test
    fun `falls back to plaintext for a file with no extension`() {
        assertThat(LanguageDetector.detect("LICENSE")).isEqualTo("plaintext")
    }
}

class WorkspaceFileTest {

    @Test
    fun `extension is derived from the name`() {
        val file = fileNamed("routes/web.php", "web.php")
        assertThat(file.extension).isEqualTo("php")
    }

    @Test
    fun `a dotfile is marked hidden`() {
        assertThat(fileNamed(".env", ".env").isHidden).isTrue()
    }

    @Test
    fun `a regular file is not hidden`() {
        assertThat(fileNamed("README.md", "README.md").isHidden).isFalse()
    }

    @Test
    fun `parentPath is null at the workspace root`() {
        assertThat(fileNamed("README.md", "README.md").parentPath).isNull()
    }

    @Test
    fun `parentPath is the containing directory`() {
        assertThat(fileNamed("app/Models/User.php", "User.php").parentPath)
            .isEqualTo("app/Models")
    }

    private fun fileNamed(relativePath: String, name: String) = WorkspaceFile(
        relativePath = relativePath,
        name = name,
        isDirectory = false,
        sizeBytes = 0,
        lastModifiedEpochMs = 0,
    )
}

class DefaultExclusionsTest {

    @Test
    fun `excludes dependency directories`() {
        assertThat(DefaultExclusions.isExcluded("node_modules")).isTrue()
        assertThat(DefaultExclusions.isExcluded("vendor")).isTrue()
        assertThat(DefaultExclusions.isExcluded(".git")).isTrue()
    }

    @Test
    fun `does not exclude ordinary project directories`() {
        assertThat(DefaultExclusions.isExcluded("app")).isFalse()
        assertThat(DefaultExclusions.isExcluded("routes")).isFalse()
        assertThat(DefaultExclusions.isExcluded("resources")).isFalse()
    }
}

class WorkspaceTest {

    @Test
    fun `untrusted workspaces deny execution network and push`() {
        assertThat(WorkspaceTrust.Untrusted.allowsExecution).isFalse()
        assertThat(WorkspaceTrust.Untrusted.allowsNetwork).isFalse()
        assertThat(WorkspaceTrust.Untrusted.allowsGitPush).isFalse()
    }

    @Test
    fun `a blank workspace name is rejected at construction`() {
        val error = runCatching {
            Workspace(
                id = WorkspaceId("a"),
                name = "  ",
                rootPath = "/data/ws/a",
                trust = WorkspaceTrust.Untrusted,
                framework = DetectedFramework.Unknown,
                createdAtEpochMs = 0,
                lastOpenedAtEpochMs = 0,
            )
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a blank workspace id is rejected at construction`() {
        assertThat(runCatching { WorkspaceId("") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `framework display names are human readable`() {
        assertThat(DetectedFramework.Laravel.displayName).isEqualTo("Laravel")
        assertThat(DetectedFramework.Unknown.displayName).isEqualTo("Project")
    }
}
