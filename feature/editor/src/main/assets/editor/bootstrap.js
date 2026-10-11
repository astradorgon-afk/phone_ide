/*
 * Monaco loader bootstrap.
 *
 * This lives in its own file rather than an inline <script> for one specific reason: the
 * page's Content-Security-Policy is `script-src 'self'`, which blocks inline execution. An
 * earlier version had this code inline and Monaco silently never initialised — the only
 * evidence was a CSP violation in the WebView console, found by running the app on a device.
 *
 * The alternative fix would have been to add 'unsafe-inline' to script-src. That was rejected:
 * this page renders untrusted project content (RISK-010), and re-enabling inline script to
 * save one file is exactly the wrong trade.
 *
 * Load order in index.html matters: bridge.js, then loader.js (defines `require`), then this.
 *
 * Workers load through a Blob URL, not the asset origin. `importScripts` inside a worker
 * enforces script MIME strictly, and what the asset loader serves for workerMain.js has
 * broken this on real hardware: a permanently black editor with no input and no error —
 * observed on a phone as "I can't type anything". Fetching the source as text does not
 * care about MIME, and the Blob carries its own type. Allowed by `worker-src 'self' blob:`
 * in the page policy, deliberately. Language workers are unused (no LSP is wired up), so
 * one worker URL serves every label.
 */
(function () {
  'use strict';

  // The AMD loader needs a base path within the asset origin. Monaco's workers start from this
  // same origin, which is why the page is served through WebViewAssetLoader over
  // https://appassets.androidplatform.net rather than file:// — a file:// origin is opaque and
  // breaks both same-origin XHR and worker construction.
  require.config({ paths: { vs: '../monaco/vs' } });

  // bridge.js must have run first (it defines __mfFail). If it didn't, say so loudly rather
  // than dying silently two scripts later.
  if (typeof window.__mfFail !== 'function') {
    var early = document.getElementById('fallback');
    if (early) {
      early.style.display = 'block';
      early.textContent = 'Editor bridge failed to load.';
    }
    return;
  }

  // Same for the AMD loader: without `require`, the config call below throws a bare
  // ReferenceError that no callback observes. bridge.js ran (checked above), so fail()
  // is available to say it plainly.
  if (typeof require === 'undefined') {
    window.__mfFail('Monaco loader failed to load.');
    return;
  }

  function boot() {
    require(
      ['vs/editor/editor.main'],
      function () {
        window.__mfInit();
      },
      function (err) {
        window.__mfFail('Monaco failed to load: ' + (err && err.message ? err.message : err));
      },
    );
  }

  window.MonacoEnvironment = {
    getWorkerUrl: function () {
      if (!window.__mfWorkerUrl) {
        throw new Error('Monaco worker URL not ready');
      }
      return window.__mfWorkerUrl;
    }
  };

  fetch('../monaco/vs/base/worker/workerMain.js')
    .then(function (response) {
      if (!response.ok) {
        throw new Error('worker source unavailable: ' + response.status);
      }
      return response.text();
    })
    .then(function (text) {
      window.__mfWorkerUrl = URL.createObjectURL(
        new Blob([text], { type: 'application/javascript' }),
      );
      boot();
    })
    .catch(function (err) {
      window.__mfFail('Monaco worker setup failed: ' + (err && err.message ? err.message : err));
    });
})();
