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
 */
(function () {
  'use strict';

  // The AMD loader needs a base path within the asset origin. Monaco's workers start from this
  // same origin, which is why the page is served through WebViewAssetLoader over
  // https://appassets.androidplatform.net rather than file:// — a file:// origin is opaque and
  // breaks both same-origin XHR and worker construction.
  require.config({ paths: { vs: '../monaco/vs' } });

  require(
    ['vs/editor/editor.main'],
    function () {
      window.__mfInit();
    },
    function (err) {
      window.__mfFail('Monaco failed to load: ' + (err && err.message ? err.message : err));
    },
  );
})();
