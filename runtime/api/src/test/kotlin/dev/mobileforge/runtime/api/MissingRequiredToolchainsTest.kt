package dev.mobileforge.runtime.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What "the developer tools are not installed" means, in one place.
 *
 * The Projects screen and the Shell pane both show a notice from this answer, so a wrong answer
 * is either a notice that never appears for someone with no PHP, or one that nags someone who has
 * everything.
 */
class MissingRequiredToolchainsTest {

    @Test fun `a fresh install is missing everything, in install order`() {
        assertThat(missingRequiredToolchains(emptyList()))
            .containsExactly("base", "php", "composer")
            .inOrder()
    }

    @Test fun `a complete install is missing nothing`() {
        assertThat(missingRequiredToolchains(listOf("base", "php", "composer"))).isEmpty()
    }

    @Test fun `a partial install names exactly what is left`() {
        assertThat(missingRequiredToolchains(listOf("base"))).containsExactly("php", "composer")
        assertThat(missingRequiredToolchains(listOf("base", "php"))).containsExactly("composer")
    }

    @Test fun `extra tools do not stand in for required ones`() {
        // opencode and nginx are optional; having them says nothing about PHP.
        assertThat(missingRequiredToolchains(listOf("opencode", "nginx")))
            .containsExactly("base", "php", "composer")
    }

    @Test fun `matching is exact, not by prefix`() {
        // "php-extras" must not count as "php".
        assertThat(missingRequiredToolchains(listOf("php-extras", "basement")))
            .containsExactly("base", "php", "composer")
    }

    @Test fun `duplicates and order of the installed list do not matter`() {
        assertThat(missingRequiredToolchains(listOf("composer", "base", "base", "php"))).isEmpty()
    }
}
