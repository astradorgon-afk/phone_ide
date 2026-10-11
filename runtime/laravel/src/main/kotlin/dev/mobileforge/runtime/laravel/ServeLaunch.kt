package dev.mobileforge.runtime.laravel

import dev.mobileforge.runtime.api.ProcessSpec

/**
 * The two ways this IDE can put a Laravel project on a local port.
 *
 * Both are *options the user picks*, not modes the app guesses at:
 *
 *  - [artisanServeSpec] runs the framework's own `php artisan serve`. It is the default because
 *    it is what a Laravel developer already types, it reloads itself when `.env` changes, and it
 *    needs nothing beyond the PHP bundle.
 *
 *  - [nginxSpec] runs a real nginx in front of `php-cgi` (the FastCGI SAPI the PHP bundle already
 *    ships). It is what someone reaches for when the single-process built-in server is not
 *    enough — static files without booting PHP, keep-alive, concurrency.
 *
 * Everything here is pure: turning a choice (port, paths) into a [ProcessSpec] or a config file
 * body, with no Android and no I/O, so it is testable on the JVM and identical on every device.
 */
object ServeLaunch {

    /** The port every Laravel developer expects. Only a default; the user may change it. */
    const val DEFAULT_PORT = 8000

    /**
     * `php artisan serve --host=127.0.0.1 --port=<port>`.
     *
     * `--host` is pinned to loopback on purpose: a dev server reachable from the whole LAN is a
     * surprise nobody asked for, and the supported way to reach it from a machine is
     * `adb forward`. The port is always passed explicitly, so Laravel's own "try the next port"
     * fallback never engages and the URL the UI shows is the URL that actually bound.
     *
     * Stop is a SIGTERM to this process. Laravel's `serve` command traps termination signals and
     * stops the `php -S` child it started (`ServeCommand::startProcess`), so stopping the parent
     * releases the port rather than orphaning the child that holds it.
     */
    fun artisanServeSpec(phpPath: String, projectRoot: String, port: Int): ProcessSpec =
        ProcessSpec(
            executable = phpPath,
            arguments = listOf(
                ARTISAN,
                "serve",
                "--host=127.0.0.1",
                "--port=$port",
            ),
            workingDirectory = projectRoot,
            shell = false,
        )

    /**
     * `php-cgi -b 127.0.0.1:<fastCgiPort>` — the FastCGI endpoint nginx forwards `.php` to.
     *
     * `PHP_FCGI_CHILDREN` gives it a small pool so a page that makes a sub-request does not
     * deadlock on a single worker. This is not php-fpm: the PHP bundle does not build the FPM
     * SAPI, and php-cgi's own FastCGI mode is complete for a single-site dev server.
     */
    fun phpCgiSpec(phpCgiPath: String, projectRoot: String, fastCgiPort: Int): ProcessSpec =
        ProcessSpec(
            executable = phpCgiPath,
            arguments = listOf("-b", "127.0.0.1:$fastCgiPort"),
            workingDirectory = projectRoot,
            environment = mapOf("PHP_FCGI_CHILDREN" to "2"),
            shell = false,
        )

    /**
     * `nginx -p <workDir> -c <configPath>`.
     *
     * `-p` points nginx at a writable directory for its pid, temp files and logs; without it
     * nginx would try to write under its install prefix, which is read-only in practice and
     * definitely not ours to scribble in. `-c` selects the per-run config [renderNginxConfig]
     * produced.
     */
    fun nginxSpec(nginxPath: String, workDir: String, configPath: String): ProcessSpec =
        ProcessSpec(
            executable = nginxPath,
            arguments = listOf("-p", workDir, "-c", configPath),
            workingDirectory = workDir,
            shell = false,
        )

    /**
     * The FastCGI port that pairs with a given HTTP port.
     *
     * Offset by one so the two cannot collide, and wrapped to a fixed value only for the
     * pathological case of the user choosing the very top of the range.
     */
    fun fastCgiPortFor(httpPort: Int): Int = if (httpPort >= MAX_PORT) FALLBACK_FASTCGI_PORT else httpPort + 1

    /** The file the generated nginx config is written to, inside a work directory. */
    fun configFileName(): String = NGINX_CONF

    /**
     * Renders the whole nginx configuration for one project.
     *
     * Deliberately self-contained: no `include mime.types` and no `include fastcgi_params`,
     * because the bundle's config layout is not part of any contract we control and a missing
     * include is a startup failure. The handful of MIME types a Laravel page needs and the
     * FastCGI parameters `php-cgi` requires are inlined instead.
     *
     * `daemon off` is the load-bearing line. nginx normally forks into the background and the
     * process we launched exits immediately — the process manager would read that as "the server
     * crashed". Foreground mode keeps the process we started the process that serves.
     *
     * The template uses `§` wherever nginx's own `$` is meant, then substitutes it at the end
     * (`$uri` and friends would otherwise be read as Kotlin string interpolation).
     */
    fun renderNginxConfig(
        port: Int,
        fastCgiPort: Int,
        documentRoot: String,
        workDir: String,
    ): String {
        val template = """
            # Generated by MobileForge. Rewritten every time the server starts; do not edit.
            worker_processes 1;
            daemon off;
            pid ${workDir}/nginx.pid;
            error_log stderr warn;

            events {
                worker_connections 256;
            }

            http {
                access_log off;
                default_type application/octet-stream;

                # Named explicitly so nothing tries to write into the read-only install prefix.
                client_body_temp_path ${workDir}/client_body;
                fastcgi_temp_path ${workDir}/fastcgi;

                # Inlined rather than included: the bundle's config layout is not a contract, and
                # a missing include file would be a startup failure. Enough for a Laravel page.
                types {
                    text/html html htm;
                    text/css css;
                    application/javascript js mjs;
                    application/json json;
                    image/svg+xml svg;
                    image/png png;
                    image/jpeg jpg jpeg;
                    image/gif gif;
                    image/x-icon ico;
                    image/webp webp;
                    font/woff2 woff2;
                    font/woff woff;
                    text/plain txt;
                    application/xml xml;
                    application/pdf pdf;
                }

                server {
                    listen 127.0.0.1:${port};
                    server_name localhost;
                    root ${documentRoot};
                    index index.php index.html;

                    location / {
                        try_files §uri §uri/ /index.php?§query_string;
                    }

                    # Dotfiles are never served. `.well-known` is the one exception the web
                    # expects, so it is let through rather than 403'd.
                    location ~ /\.(?!well-known).* {
                        deny all;
                    }

                    location ~ \.php§ {
                        fastcgi_pass 127.0.0.1:${fastCgiPort};
                        fastcgi_index index.php;
                        fastcgi_split_path_info ^(.+\.php)(/.+)§;

                        fastcgi_param SCRIPT_FILENAME §document_root§fastcgi_script_name;
                        fastcgi_param PATH_INFO §fastcgi_path_info;
                        fastcgi_param QUERY_STRING §query_string;
                        fastcgi_param REQUEST_METHOD §request_method;
                        fastcgi_param CONTENT_TYPE §content_type;
                        fastcgi_param CONTENT_LENGTH §content_length;
                        fastcgi_param SCRIPT_NAME §fastcgi_script_name;
                        fastcgi_param REQUEST_URI §request_uri;
                        fastcgi_param DOCUMENT_URI §document_uri;
                        fastcgi_param DOCUMENT_ROOT §document_root;
                        fastcgi_param SERVER_PROTOCOL §server_protocol;
                        fastcgi_param REQUEST_SCHEME §scheme;
                        fastcgi_param GATEWAY_INTERFACE CGI/1.1;
                        fastcgi_param SERVER_SOFTWARE nginx;
                        fastcgi_param REMOTE_ADDR §remote_addr;
                        fastcgi_param REMOTE_PORT §remote_port;
                        fastcgi_param SERVER_ADDR §server_addr;
                        fastcgi_param SERVER_PORT §server_port;
                        fastcgi_param SERVER_NAME §server_name;
                        # php-cgi refuses a request without this; it is not optional.
                        fastcgi_param REDIRECT_STATUS 200;
                    }
                }
            }
        """.trimIndent()
        return template.replace(NGINX_DOLLAR, '$')
    }

    private const val ARTISAN = "artisan"
    private const val NGINX_CONF = "nginx.conf"

    /** Stands in for an nginx `$` while the template is a Kotlin string literal. */
    private const val NGINX_DOLLAR = '§'

    private const val MAX_PORT = 65_535
    private const val FALLBACK_FASTCGI_PORT = 9050
}
