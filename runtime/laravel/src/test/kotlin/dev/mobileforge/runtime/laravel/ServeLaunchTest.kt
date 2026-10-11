package dev.mobileforge.runtime.laravel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The serve launch specs.
 *
 * These are pure, so the tests pin the two things that are easy to get wrong and expensive to
 * discover on a device: the argument vector (a wrong flag is a server that never binds) and the
 * nginx config text (a stray Kotlin interpolation marker or a missing `$` is a config nginx
 * refuses to load).
 */
class ServeLaunchTest {

    @Test
    fun `artisan serve pins loopback and the chosen port`() {
        val spec = ServeLaunch.artisanServeSpec(
            phpPath = "/data/data/dev.mobileforge/files/usr/bin/php",
            projectRoot = "/data/user/0/dev.mobileforge/files/workspaces/demo",
            port = 8123,
        )

        assertThat(spec.executable).isEqualTo("/data/data/dev.mobileforge/files/usr/bin/php")
        assertThat(spec.arguments)
            .containsExactly("artisan", "serve", "--host=127.0.0.1", "--port=8123")
            .inOrder()
        assertThat(spec.workingDirectory).endsWith("workspaces/demo")
        assertThat(spec.shell).isFalse()
    }

    @Test
    fun `php-cgi listens on the paired fastcgi port`() {
        val spec = ServeLaunch.phpCgiSpec(
            phpCgiPath = "/usr/bin/php-cgi",
            projectRoot = "/project",
            fastCgiPort = 8124,
        )

        assertThat(spec.arguments).containsExactly("-b", "127.0.0.1:8124").inOrder()
        assertThat(spec.environment).containsEntry("PHP_FCGI_CHILDREN", "2")
    }

    @Test
    fun `nginx runs in the foreground with the generated config`() {
        val spec = ServeLaunch.nginxSpec(
            nginxPath = "/usr/bin/nginx",
            workDir = "/cache/serve/demo",
            configPath = "/cache/serve/demo/nginx.conf",
        )

        assertThat(spec.arguments)
            .containsExactly("-p", "/cache/serve/demo", "-c", "/cache/serve/demo/nginx.conf")
            .inOrder()
    }

    @Test
    fun `fastcgi port never collides with the http port`() {
        assertThat(ServeLaunch.fastCgiPortFor(8000)).isEqualTo(8001)
        // Top of the range wraps instead of issuing an impossible port.
        assertThat(ServeLaunch.fastCgiPortFor(65_535)).isEqualTo(9050)
    }

    @Test
    fun `nginx config is self-contained and free of kotlin interpolation markers`() {
        val config = ServeLaunch.renderNginxConfig(
            port = 8000,
            fastCgiPort = 8001,
            documentRoot = "/data/workspaces/demo/public",
            workDir = "/data/cache/serve/demo",
        )

        // No un-substituted template marker survived, and the nginx variables are real `$`.
        assertThat(config).doesNotContain("§")
        assertThat(config).contains("listen 127.0.0.1:8000;")
        assertThat(config).contains("fastcgi_pass 127.0.0.1:8001;")
        assertThat(config).contains("root /data/workspaces/demo/public;")
        assertThat(config).contains("try_files \$uri \$uri/ /index.php?\$query_string;")
        assertThat(config).contains("fastcgi_param SCRIPT_FILENAME \$document_root\$fastcgi_script_name;")
        // php-cgi rejects a FastCGI request without REDIRECT_STATUS.
        assertThat(config).contains("fastcgi_param REDIRECT_STATUS 200;")
        // Foreground mode is what keeps the process we launched the process that serves.
        assertThat(config).contains("daemon off;")
        // No includes: the bundle's layout is not a contract, and a missing include file would
        // be a startup failure. Checked per line so the explanatory comment does not trip it.
        assertThat(config.lines().none { it.trimStart().startsWith("include ") }).isTrue()
        assertThat(config).contains("location ~ \\.php\$ {")
    }
}
