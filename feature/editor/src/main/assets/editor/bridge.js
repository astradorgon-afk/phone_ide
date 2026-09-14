/*
 * JavaScript half of the editor bridge.
 *
 * Contract (mirrors EditorBridgeProtocol.kt):
 *   Kotlin -> here : window.__mfReceive(jsonString)
 *   here -> Kotlin : MobileForgeBridge.postMessage(jsonString)
 *
 * Design rules this file follows:
 *   - Every outbound message is a plain object serialised with JSON.stringify. No function
 *     references, no DOM nodes, nothing that could smuggle a capability across.
 *   - Buffer content is NEVER pushed on change. Change events carry a version counter only;
 *     Kotlin asks for content explicitly when it needs to save. Shipping the whole document on
 *     every keystroke is what makes WebView editors feel sluggish on a phone.
 *   - Unknown inbound message types are ignored silently rather than guessed at.
 *   - Kotlin owns the document. This file holds no state that cannot be rebuilt from a
 *     setDocument command, so a WebView reload costs nothing but a repaint.
 */
(function () {
  'use strict';

  var editor = null;
  var currentPath = null;
  var version = 0;
  var pendingOptions = null;
  var pendingDocument = null;

  var PROTOCOL_VERSION = 1;

  function post(message) {
    try {
      if (window.MobileForgeBridge && window.MobileForgeBridge.postMessage) {
        window.MobileForgeBridge.postMessage(JSON.stringify(message));
      }
    } catch (e) {
      // Never throw across the bridge: an exception here would be invisible to Kotlin.
    }
  }

  function fail(message) {
    var el = document.getElementById('fallback');
    if (el) {
      el.style.display = 'block';
      el.textContent = message;
    }
    post({ type: 'error', message: String(message) });
  }

  window.__mfFail = fail;

  window.__mfInit = function () {
    try {
      monaco.editor.defineTheme('mf-dark', {
        base: 'vs-dark',
        inherit: true,
        rules: [],
        colors: {
          'editor.background': '#0D1117',
          'editorGutter.background': '#0D1117',
          'editorLineNumber.foreground': '#4B5563',
          'editorLineNumber.activeForeground': '#9BA6B2',
          'editor.lineHighlightBackground': '#161B22',
          'editorIndentGuide.background1': '#1F2630'
        }
      });
      monaco.editor.defineTheme('mf-light', {
        base: 'vs',
        inherit: true,
        rules: [],
        colors: {
          'editor.background': '#FBFCFD',
          'editorGutter.background': '#FBFCFD'
        }
      });

      editor = monaco.editor.create(document.getElementById('container'), {
        value: '',
        language: 'plaintext',
        theme: 'mf-dark',
        automaticLayout: true,
        fontSize: 13,
        lineNumbers: 'on',
        wordWrap: 'on',
        minimap: { enabled: false },
        scrollBeyondLastLine: false,
        renderWhitespace: 'selection',
        // Touch ergonomics: a phone has no hover, and the default 10px scrollbars are not
        // reliably hittable with a thumb.
        scrollbar: { verticalScrollbarSize: 14, horizontalScrollbarSize: 14 },
        // Suggestions on a soft keyboard fight the IME more than they help; Phase 1 leaves
        // them off until real-device testing says otherwise.
        quickSuggestions: false,
        occurrencesHighlight: 'off',
        renderLineHighlight: 'line',
        padding: { top: 8, bottom: 96 },
        contextmenu: false
      });

      editor.onDidChangeModelContent(function () {
        version += 1;
        post({ type: 'changed', path: currentPath || '', version: version });
      });

      editor.onDidChangeCursorPosition(function (e) {
        post({
          type: 'cursor',
          line: e.position.lineNumber,
          column: e.position.column
        });
      });

      editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, function () {
        // The editor only ANNOUNCES the intent. Kotlin decides whether a save happens,
        // because Kotlin is where workspace trust and read-only state live.
        post({ type: 'saveRequested', path: currentPath || '' });
      });

      // Commands may have arrived while Monaco was still loading.
      if (pendingOptions) { applyOptions(pendingOptions); pendingOptions = null; }
      if (pendingDocument) { applyDocument(pendingDocument); pendingDocument = null; }

      post({ type: 'ready', protocolVersion: PROTOCOL_VERSION });
    } catch (e) {
      fail('Editor initialisation failed: ' + e);
    }
  };

  function applyDocument(cmd) {
    var model = editor.getModel();
    if (model) { model.dispose(); }

    var uri = monaco.Uri.parse('inmemory://workspace/' + encodeURI(cmd.path));
    var existing = monaco.editor.getModel(uri);
    if (existing) { existing.dispose(); }

    var newModel = monaco.editor.createModel(cmd.content, cmd.languageId, uri);
    editor.setModel(newModel);
    editor.updateOptions({ readOnly: !!cmd.readOnly });
    currentPath = cmd.path;
    version = 0;
    editor.setScrollTop(0);
  }

  function applyOptions(cmd) {
    editor.updateOptions({
      fontSize: cmd.fontSizeSp,
      wordWrap: cmd.wordWrap ? 'on' : 'off',
      minimap: { enabled: !!cmd.minimap }
    });
  }

  window.__mfReceive = function (payload) {
    var cmd;
    try {
      cmd = JSON.parse(payload);
    } catch (e) {
      return; // Malformed input is dropped, never guessed at.
    }
    if (!cmd || typeof cmd.type !== 'string') { return; }

    // Buffer commands that arrive before Monaco has mounted rather than losing them.
    if (!editor) {
      if (cmd.type === 'setDocument') { pendingDocument = cmd; }
      if (cmd.type === 'setOptions') { pendingOptions = cmd; }
      return;
    }

    switch (cmd.type) {
      case 'setDocument':
        applyDocument(cmd);
        break;

      case 'setTheme':
        monaco.editor.setTheme(cmd.theme === 'light' ? 'mf-light' : 'mf-dark');
        break;

      case 'setOptions':
        applyOptions(cmd);
        break;

      case 'requestContent':
        post({
          type: 'content',
          requestId: cmd.requestId,
          path: currentPath || '',
          content: editor.getValue()
        });
        break;

      case 'runAction':
        runAction(cmd.action);
        break;

      case 'setDiagnostics':
        setDiagnostics(cmd.diagnostics);
        break;

      default:
        break; // Unknown type: ignore.
    }
  };

  function runAction(action) {
    switch (action) {
      case 'undo': editor.trigger('mf', 'undo', null); break;
      case 'redo': editor.trigger('mf', 'redo', null); break;
      case 'find': editor.trigger('mf', 'actions.find', null); break;
      case 'replace': editor.trigger('mf', 'editor.action.startFindReplaceAction', null); break;
      case 'format': editor.trigger('mf', 'editor.action.formatDocument', null); break;
      case 'selectAll': editor.trigger('mf', 'editor.action.selectAll', null); break;
      case 'commentLine': editor.trigger('mf', 'editor.action.commentLine', null); break;
      case 'focus': editor.focus(); break;
      default: break;
    }
  }

  var SEVERITY = {
    error: 8,   // monaco.MarkerSeverity.Error
    warning: 4,
    info: 2,
    hint: 1
  };

  function setDiagnostics(list) {
    var model = editor.getModel();
    if (!model || !Array.isArray(list)) { return; }
    monaco.editor.setModelMarkers(model, 'mobileforge', list.map(function (d) {
      return {
        startLineNumber: d.line,
        startColumn: d.column,
        endLineNumber: d.endLine,
        endColumn: d.endColumn,
        message: d.message,
        severity: SEVERITY[d.severity] || SEVERITY.info
      };
    }));
  }
})();
