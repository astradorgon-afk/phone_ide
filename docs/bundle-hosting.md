# Hosting toolchain bundles

MobileForge installs toolchains by **fetching** them, not by asking you to find a file. The
app ships a catalog (`app/src/main/assets/bundles/catalog.json`) that lists, for each tool and
CPU architecture, the version and the file name to fetch. Everything a person used to have to
know — which ZIP, which manifest, which version — is decided by the build.

## How a download happens

1. The catalog names a `base_url` and a list of bundles (`<path>.json` + `<path>.zip`).
2. `CatalogInstaller` downloads each bundle's **manifest** first (a few hundred bytes).
3. The manifest is verified the same way a hand-imported one is: ABI, prefix, page size,
   digest. An incompatible bundle is refused **before** its archive is fetched.
4. Only then is the archive downloaded, its SHA-256 checked, and the files installed.

Order is fixed (`base` → `php` → `composer`), and installing a tool whose dependency is absent
is refused, so a half-finished run leaves a working prefix rather than a broken one.

## Where bundles come from

`base_url` in the catalog points at the published location. Two things can override it:

* **A debug build** uses `app/src/debug/assets/bundles/catalog.json`, which points at
  `http://127.0.0.1:8080` — the machine you develop on.
* **Advanced options → Source URL** in the Toolchains screen, for anyone who hosts bundles
  themselves. Blank restores the built-in source.

Plain `http://` is accepted only for `localhost`, the loopback range, and the emulator's host
alias (`10.0.2.2`) — the same three addresses `res/xml/network_security_config.xml` reopens at
the OS level. Anything else must be `https://`, because a bundle becomes executable files in
the app's prefix and Android's network security config cannot whitelist address ranges.

## Serving bundles from this machine

```powershell
# Terminal 1: serve everything built here (build/toolchain-bundles + embedded assets)
./tools/serve-bundles.ps1 -Port 8080

# Terminal 2: make the phone's localhost point at this machine
adb reverse tcp:8080 tcp:8080
```

Then, in the app: **Toolchains → Advanced options → Source URL**, enter
`http://127.0.0.1:8080`, Save, and press **Download and install**.

## Building the bundles

Bundles are produced from termux-packages sources with the prefix repointed, one CPU
architecture at a time. Base and PHP are built and packaged together — PHP's dependency
closure contains Base's, and the prefix is deliberately not persisted between runs, so
building them apart would compile OpenSSL, libcurl and the rest twice:

```bash
bash tools/build-base-php.sh aarch64    # base-1.0.0-arm64-v8a + php-8.5.1-arm64-v8a
bash tools/build-base-php.sh x86_64     # the same for the emulator
bash tools/build-composer.sh aarch64    # composer-2.10.3-arm64-v8a (fast; a phar)
bash tools/build-nginx.sh aarch64       # nginx-1.31.6-arm64-v8a (only if you want a real front end)
```

The compile step is hours for a first build of an architecture; packaging is seconds once the
`.deb`s exist. Everything lands in `build/toolchain-bundles/`, which is exactly what
`tools/serve-bundles.ps1` serves.

## Publishing for real

Bundles are ordinary files: for each entry in the catalog, publish `<path>.json` and
`<path>.zip` at `<base_url>/<path>.json` and `<base_url>/<path>.zip`. Any static host works
(GitHub Releases, S3, a CDN). Update `base_url` and the entry list in the catalog, and ship
the APK.

`tools/publish-bundles.ps1` does the error-prone part of that by hand:

```powershell
./tools/publish-bundles.ps1 -BaseUrl https://bundles.example.com
```

It stages every bundle into `build/bundle-site/`, **verifying each archive against its
manifest's `sha256` and `size_bytes` first** and refusing to publish otherwise — a manifest
that disagrees with its archive installs on zero devices and only fails after a user presses
"Download and install". It also rewrites `base_url` in the shipped catalog (refusing a
`base_url` that is not `https`, except loopback for a dry run) and copies the catalog beside
the bundles. Add `-UploadCommand 'aws s3 sync {out} s3://bucket --delete'` to push it in the
same step.

The catalog lists every architecture a build publishes; the app keeps only the entries whose
`abi` matches the device (`Build.SUPPORTED_ABIS.first()`), so one catalog serves phones
(`arm64-v8a`) and emulators (`x86_64`) alike.

There is no signature trust yet: the checksum in a manifest proves the archive matches *that
manifest*, not who published it. Treat the source URL as the trust boundary (ADR-011).
