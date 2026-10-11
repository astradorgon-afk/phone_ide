# Running a server on the phone

The **Serve** pane in the workspace (wide-screen: a sidebar; narrow: a tab) lets you run a local
server for the open Laravel project. Two options are available:

## `php artisan serve` (default)

Runs `php artisan serve --host=127.0.0.1 --port=N` via PHP's built-in development server. This
is the simplest and is what most Laravel developers type. Laravel traps termination signals and
stops the spawned `php -S` child, so pressing Stop frees the bound port.

- Chooses an explicit port (editable; digits only; defaults to `8000`). Ports below 1024 are
  rejected on Android.
- Starts/stops with the Start/Stop button. The URL shown (`http://127.0.0.1:<port>`) can be
  forwarded from the host via `adb forward tcp:<port> tcp:<port>`.

`ServeVerificationTest` proves end-to-end: `composer create-project laravel/laravel` on the device
+ `php artisan serve` → HTTP 200 with a real Laravel welcome page, and that PHP can `proc_open`
a child (`out=CHILD-OK code=0`).

## nginx + php-cgi

Runs a real nginx in front of `php-cgi`. Use this when you want static files served without
booting PHP, keep-alive, or better concurrency.

- If nginx is missing, the UI marks it as **not installed** and disables starting, with an
  inline "Install missing" hint pointing to Settings → Toolchains.
- Starts `php-cgi -b 127.0.0.1:<fastCgiPort>` (fastCGI) first, then `nginx -p <workDir> -c nginx.conf`
  (the web server). FastCGI port = HTTP port + 1.
- The config is generated on every start: self-contained (no `include mime.types`/`fastcgi_params`),
  `daemon off` so the launched process remains the server process, inlined types + FastCGI
  parameters, correct `try_files` for Laravel's front controller, dotfiles denied except
  `.well-known`.
- Stop stops nginx first, then php-cgi (guaranteeing no orphan backend).

The app includes `NginxServeVerificationTest` that proves nginx serves both a static HTML file
and a PHP file via FastCGI over localhost.

## Reaching it from your computer

All servers bind `127.0.0.1` on the phone (loopback). Forward the HTTP port from the host:

```powershell
adb forward tcp:<port> tcp:<port>
# then open http://127.0.0.1:<port>
```

Note: `adb reverse` is host→device (useful for serving bundles); the dev server needs port
forwarding device→host.

## While running

- A **foreground service** (`RuntimeForegroundService`) keeps user-started servers alive when
  MobileForge is backgrounded. The notification shows only while there are running processes and
  summarizes which servers are active.
- Output from the running server (nginx errors, php-cgi notices, artisan output lines) appears
  in the Serve pane. A deliberate Stop is never reported as a crash.
- When the workspace closes (`onCleared`), all tracked servers are stopped.
