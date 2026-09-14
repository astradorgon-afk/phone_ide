package dev.mobileforge.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CommandRiskClassifierTest {

    @Test
    fun `flags recursive force delete as destructive`() {
        val risk = CommandRiskClassifier.classify("rm -rf storage/framework/cache")
        assertThat(risk).isInstanceOf(CommandRisk.Destructive::class.java)
        assertThat(risk.requiresExplicitConfirmation).isTrue()
    }

    @Test
    fun `flags git reset hard as destructive with a reason`() {
        val risk = CommandRiskClassifier.classify("git reset --hard HEAD")
        assertThat(risk).isInstanceOf(CommandRisk.Destructive::class.java)
        assertThat((risk as CommandRisk.Destructive).reason).contains("uncommitted")
    }

    @Test
    fun `flags long-form force push as destructive`() {
        assertThat(CommandRiskClassifier.classify("git push --force origin main"))
            .isInstanceOf(CommandRisk.Destructive::class.java)
    }

    @Test
    fun `flags short-form force push as destructive`() {
        assertThat(CommandRiskClassifier.classify("git push -f origin main"))
            .isInstanceOf(CommandRisk.Destructive::class.java)
    }

    @Test
    fun `flags migrate fresh as destructive`() {
        assertThat(CommandRiskClassifier.classify("php artisan migrate:fresh --seed"))
            .isInstanceOf(CommandRisk.Destructive::class.java)
    }

    @Test
    fun `flags drop table as destructive`() {
        assertThat(CommandRiskClassifier.classify("mysql -e DROP TABLE users"))
            .isInstanceOf(CommandRisk.Destructive::class.java)
    }

    @Test
    fun `flags git clean as destructive`() {
        assertThat(CommandRiskClassifier.classify("git clean -fd"))
            .isInstanceOf(CommandRisk.Destructive::class.java)
    }

    @Test
    fun `treats composer install as package installation not ordinary`() {
        // Distinct from Ordinary because it runs third-party lifecycle scripts (RISK-015).
        val risk = CommandRiskClassifier.classify("composer install")
        assertThat(risk).isEqualTo(CommandRisk.PackageInstall)
        assertThat(risk.requiresExplicitConfirmation).isTrue()
    }

    @Test
    fun `treats npm install as package installation`() {
        assertThat(CommandRiskClassifier.classify("npm install"))
            .isEqualTo(CommandRisk.PackageInstall)
    }

    @Test
    fun `treats git clone as network`() {
        assertThat(CommandRiskClassifier.classify("git clone https://github.com/laravel/laravel"))
            .isEqualTo(CommandRisk.Network)
    }

    @Test
    fun `treats curl as network`() {
        assertThat(CommandRiskClassifier.classify("curl https://example.com"))
            .isEqualTo(CommandRisk.Network)
    }

    @Test
    fun `treats an ordinary artisan command as ordinary`() {
        assertThat(CommandRiskClassifier.classify("php artisan route:list"))
            .isEqualTo(CommandRisk.Ordinary)
    }

    @Test
    fun `classifies an empty command as unknown rather than safe`() {
        // Unknown must never be read as a safety signal by the permission engine.
        assertThat(CommandRiskClassifier.classify("   ")).isEqualTo(CommandRisk.Unknown)
    }

    @Test
    fun `is case insensitive`() {
        assertThat(CommandRiskClassifier.classify("GIT RESET --HARD"))
            .isInstanceOf(CommandRisk.Destructive::class.java)
    }

    @Test
    fun `detects a destructive command hidden after a separator`() {
        assertThat(CommandRiskClassifier.classify("echo hi && rm -rf /data"))
            .isInstanceOf(CommandRisk.Destructive::class.java)
    }

    @Test
    fun `ordinary commands do not require explicit confirmation`() {
        assertThat(CommandRiskClassifier.classify("php artisan route:list").requiresExplicitConfirmation)
            .isFalse()
    }
}
