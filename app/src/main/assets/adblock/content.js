/*
 * Nexa content script — injected at document start into every frame.
 *
 * Mirrors uBlock Origin's content-script role: it asks the native filtering
 * engine (through a synchronous Java bridge) for the cosmetic filters that
 * apply to this frame, applies them through constructable stylesheets (not
 * subject to page CSP), runs procedural filters, and surveys the DOM for
 * class/id tokens so low-generic filters are fetched only when they can
 * match. The same payload lists the scriptlets whose filters target this
 * frame's site; they run from the bundled library (`scriptlets/*.js`, which
 * the native side concatenates in front of this file inside one closure) so
 * no code is evaluated from strings and page CSP / Trusted Types never apply.
 * `$csp` filters arrive as policies that are applied through a transient
 * `<meta http-equiv>` before any page script runs. Every page API this file
 * or the scriptlets patch is cloaked (see `H.cloak` in 00-core.js).
 */
(function () {
  'use strict';
  var BRIDGE_NAME = '__NEXA_BRIDGE__';
  var bridge = window[BRIDGE_NAME];
  if (!bridge || typeof bridge.cosmetics !== 'function') return;
  try { delete window[BRIDGE_NAME]; } catch (e) { /* non-configurable on some builds */ }

  var doc = document;
  if (!(doc instanceof HTMLDocument) && !(doc instanceof Document)) return;

  var ancestors = (location.ancestorOrigins && location.ancestorOrigins.length) ? location.ancestorOrigins : null;
  var frameUrl = location.href;
  if (!/^https?:/.test(frameUrl)) {
    // about:blank / srcdoc frames inherit their parent's origin.
    if (!ancestors) return;
    frameUrl = ancestors[0] + '/';
  }
  var topUrl = ancestors ? ancestors[ancestors.length - 1] + '/' : '';

  // ── Popup attribution ────────────────────────────────────────────────
  // WebView does not tell the app which frame opened a window. Report it
  // (synchronously, before the native window request) so popups from
  // embedded players can be told apart from links the user tapped.
  if (typeof bridge.popup === 'function') {
    var reportPopup = function (url, kind) {
      var absolute = '';
      try { absolute = url ? new URL(String(url), location.href).href : ''; } catch (e) { /* invalid URL */ }
      try { bridge.popup(frameUrl, absolute, kind); } catch (e) { /* bridge gone */ }
    };
    var nativeOpen = window.open;
    if (typeof nativeOpen === 'function' && typeof Proxy === 'function') {
      window.open = H.cloak(new Proxy(nativeOpen, {
        apply: function (target, self, args) {
          reportPopup(args[0], 'script');
          return Reflect.apply(target, self, args);
        }
      }), nativeOpen);
    }
    doc.addEventListener('click', function (event) {
      var path = typeof event.composedPath === 'function' ? event.composedPath() : [event.target];
      for (var i = 0; i < path.length; i++) {
        var node = path[i];
        if (!node || node.nodeName !== 'A' || !node.href) continue;
        var target = (node.getAttribute('target') || '').toLowerCase();
        if (target && target !== '_self' && target !== '_top' && target !== '_parent') {
          // Synthetic clicks (`a.click()` from a script) are popunders, not user links.
          reportPopup(node.href, event.isTrusted ? 'link' : 'script');
        }
        return;
      }
    }, true);
  }

  var payload;
  try {
    payload = JSON.parse(bridge.cosmetics(frameUrl, topUrl));
  } catch (e) {
    H.installToStringCloak();
    return;
  }

  // ── Content Security Policy ($csp) ───────────────────────────────────
  // A meta policy is enforced from insertion on and stays in force after
  // the element is removed, so nothing is left in the DOM. Meta policies
  // only count inside <head>; one is borrowed (or created) for the purpose.
  function applyCsp(policies) {
    var root = doc.documentElement;
    if (!root) return false;
    var head = doc.head;
    var temporary = null;
    if (!head) {
      temporary = doc.createElement('head');
      root.insertBefore(temporary, root.firstChild);
      head = temporary;
    }
    for (var i = 0; i < policies.length; i++) {
      var meta = doc.createElement('meta');
      meta.setAttribute('http-equiv', 'Content-Security-Policy');
      meta.setAttribute('content', policies[i]);
      head.appendChild(meta);
      meta.remove();
    }
    if (temporary) temporary.remove();
    return true;
  }

  if (payload.csp && payload.csp.length && !applyCsp(payload.csp)) {
    // No root element yet: apply as soon as the parser creates it.
    var cspObserver = new MutationObserver(function () {
      if (applyCsp(payload.csp)) cspObserver.disconnect();
    });
    cspObserver.observe(doc, { childList: true });
  }

  // ── Scriptlets ───────────────────────────────────────────────────────
  // Run first, before any page script: the engine only lists scriptlets
  // whose filters target this frame's site, so most frames run none.
  var calls = payload.scriptlets;
  if (calls && calls.length && typeof S === 'object') {
    for (var c = 0; c < calls.length; c++) {
      var fn = S[calls[c][0]];
      if (typeof fn !== 'function') continue;
      try { fn.apply(null, calls[c][1]); } catch (e) { /* a broken scriptlet must not stop the others */ }
    }
  }
  // Still before any page script: from here on the patches are invisible.
  H.installToStringCloak();

  // ── Stylesheets ──────────────────────────────────────────────────────
  var supportsAdopted = 'adoptedStyleSheets' in Document.prototype && typeof CSSStyleSheet === 'function' &&
    'replaceSync' in CSSStyleSheet.prototype;

  // Pages may *assign* document.adoptedStyleSheets (frameworks do), which
  // would silently drop our sheets. Keep them appended on every assignment.
  var ownSheets = [];
  var adoptedDescriptor = supportsAdopted && Object.getOwnPropertyDescriptor(Document.prototype, 'adoptedStyleSheets');
  if (adoptedDescriptor && adoptedDescriptor.get && adoptedDescriptor.set) {
    try {
      Object.defineProperty(doc, 'adoptedStyleSheets', {
        configurable: true,
        enumerable: adoptedDescriptor.enumerable,
        get: H.cloak(function () { return adoptedDescriptor.get.call(this); }, adoptedDescriptor.get),
        set: H.cloak(function (sheets) {
          var list = Array.prototype.slice.call(sheets || []);
          for (var i = 0; i < ownSheets.length; i++) if (list.indexOf(ownSheets[i]) === -1) list.push(ownSheets[i]);
          adoptedDescriptor.set.call(this, list);
        }, adoptedDescriptor.set)
      });
    } catch (e) { /* non-configurable on this build */ }
  }

  function addStyle(css) {
    if (!css) return;
    if (supportsAdopted) {
      try {
        var sheet = new CSSStyleSheet();
        sheet.replaceSync(css);
        ownSheets.push(sheet);
        doc.adoptedStyleSheets = doc.adoptedStyleSheets.concat([sheet]);
        return;
      } catch (e) { /* fall through */ }
    }
    var style = doc.createElement('style');
    style.textContent = css;
    var attach = function () {
      var parent = doc.head || doc.documentElement;
      if (parent) { parent.appendChild(style); return true; }
      return false;
    };
    if (!attach()) doc.addEventListener('DOMContentLoaded', attach, { once: true });
  }

  addStyle(payload.css);

  // ── Scheduling helpers ───────────────────────────────────────────────
  function onReady(fn) {
    if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', fn, { once: true });
    else fn();
  }

  var observers = [];
  var observer = null;
  function observe(callback) {
    observers.push(callback);
    if (observer) return;
    observer = new MutationObserver(function (mutations) {
      for (var i = 0; i < observers.length; i++) observers[i](mutations);
    });
    var start = function () {
      if (!doc.documentElement) return;
      observer.observe(doc.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'] });
    };
    if (doc.documentElement) start(); else onReady(start);
  }

  function throttle(fn, delay) {
    var timer = 0;
    var last = 0;
    return function () {
      if (timer) return;
      var wait = Math.max(0, delay - (Date.now() - last));
      timer = setTimeout(function () {
        timer = 0;
        last = Date.now();
        fn();
      }, wait);
    };
  }

  // ── Low-generic surveyor ─────────────────────────────────────────────
  if (payload.generic && typeof bridge.genericCss === 'function') {
    var seen = new Set();
    var pending = [];
    var pendingNodes = [];

    var collect = function (el) {
      var id = el.id;
      if (id && typeof id === 'string') {
        var idToken = '#' + id;
        if (!seen.has(idToken)) { seen.add(idToken); pending.push(idToken); }
      }
      var list = el.classList;
      if (list) {
        for (var i = 0; i < list.length; i++) {
          var token = '.' + list[i];
          if (!seen.has(token)) { seen.add(token); pending.push(token); }
        }
      }
    };

    var scan = function (root) {
      if (root.nodeType !== 1) return;
      collect(root);
      var nodes = root.querySelectorAll('[id],[class]');
      for (var i = 0; i < nodes.length; i++) collect(nodes[i]);
    };

    var flush = function () {
      var nodes = pendingNodes;
      pendingNodes = [];
      for (var i = 0; i < nodes.length; i++) if (nodes[i].isConnected) scan(nodes[i]);
      if (!pending.length) return;
      var tokens = pending.join(' ');
      pending = [];
      try { addStyle(bridge.genericCss(frameUrl, topUrl, tokens)); } catch (e) { /* bridge gone */ }
    };
    var scheduleFlush = throttle(flush, 100);

    onReady(function () {
      if (doc.documentElement) pendingNodes.push(doc.documentElement);
      flush();
      observe(function (mutations) {
        for (var i = 0; i < mutations.length; i++) {
          var m = mutations[i];
          if (m.type === 'attributes') { pendingNodes.push(m.target); continue; }
          var added = m.addedNodes;
          for (var j = 0; j < added.length; j++) if (added[j].nodeType === 1) pendingNodes.push(added[j]);
        }
        if (pendingNodes.length) scheduleFlush();
      });
    });
  }

  // ── Procedural filters ───────────────────────────────────────────────
  if (payload.procedural && payload.procedural.length) {
    var OPERATORS = {
      'has-text': 1, '-abp-contains': 1, 'contains': 1, 'upward': 1, 'nth-ancestor': 1, 'xpath': 1,
      'matches-css': 1, 'matches-css-before': 1, 'matches-css-after': 1, 'min-text-length': 1,
      'matches-attr': 1, 'matches-path': 1, 'matches-media': 1, 'remove': 1, 'remove-attr': 1,
      'remove-class': 1, 'style': 1, 'if': 1, 'if-not': 1, 'has': 1, '-abp-has': 1, 'not': 1, 'watch-attr': 1,
      'others': 1, 'shadow': 1, 'matches-prop': 1
    };
    // Structural elements :others() never hides.
    var KEEP_TAGS = { HEAD: 1, SCRIPT: 1, STYLE: 1, LINK: 1, META: 1, TITLE: 1, NOSCRIPT: 1, TEMPLATE: 1 };
    var ACTIONS = { 'remove': 1, 'remove-attr': 1, 'remove-class': 1, 'style': 1 };

    var toRegExp = function (arg) {
      var m = /^\/(.+)\/([imsu]*)$/.exec(arg);
      if (m) { try { return new RegExp(m[1], m[2]); } catch (e) { return null; } }
      var literal = arg.replace(/^(['"])(.*)\1$/, '$2');
      return new RegExp(literal.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
    };

    // Index of the parenthesis closing the one opened at `open`.
    var closingParen = function (s, open) {
      var depth = 0;
      var quote = '';
      for (var i = open; i < s.length; i++) {
        var c = s[i];
        if (quote) { if (c === '\\') i++; else if (c === quote) quote = ''; continue; }
        if (c === '"' || c === "'") { quote = c; continue; }
        if (c === '\\') { i++; continue; }
        if (c === '(') depth++;
        else if (c === ')') { depth--; if (depth === 0) return i; }
      }
      return -1;
    };

    var containsProcedural = function (s) {
      return /:(has-text|-abp-contains|contains|upward|nth-ancestor|xpath|matches-css|matches-css-before|matches-css-after|min-text-length|matches-attr|matches-path|matches-media|matches-prop|remove|remove-attr|remove-class|style|if|if-not|watch-attr|others|shadow)\(/.test(s);
    };

    // Splits "css:op(arg)css:op(arg)" into { prefix, ops:[{name,arg}|{css}], action }.
    var parse = function (raw) {
      var ops = [];
      var prefix = '';
      var css = '';
      var i = 0;
      var sawOperator = false;
      while (i < raw.length) {
        var c = raw[i];
        if (c === ':') {
          var m = /^:([-a-z]+)\(/.exec(raw.slice(i));
          if (m && OPERATORS[m[1]]) {
            var open = i + m[0].length - 1;
            var close = closingParen(raw, open);
            if (close === -1) return null;
            var arg = raw.slice(open + 1, close);
            var name = m[1];
            var nativeHas = (name === 'has' || name === 'not') && !containsProcedural(arg);
            if (!nativeHas) {
              if (!sawOperator) prefix = css; else if (css.trim()) ops.push({ css: css });
              css = '';
              sawOperator = true;
              ops.push({ name: name, arg: arg });
              i = close + 1;
              continue;
            }
          }
        }
        if (c === '\\') { css += raw.slice(i, i + 2); i += 2; continue; }
        css += c;
        i++;
      }
      if (!sawOperator) return null;
      if (css.trim()) ops.push({ css: css });
      var action = null;
      var last = ops[ops.length - 1];
      if (last && last.name && ACTIONS[last.name]) { action = ops.pop(); }
      for (var k = 0; k < ops.length; k++) {
        var op = ops[k];
        if (op.name === 'has' || op.name === '-abp-has' || op.name === 'if' || op.name === 'if-not' || op.name === 'not') {
          op.sub = parse(op.arg) || { prefix: op.arg, ops: [], action: null };
        }
      }
      return { prefix: prefix.trim(), ops: ops, action: action };
    };

    var select = function (task, root) {
      var nodes;
      var prefix = task.prefix;
      if (!prefix) {
        nodes = root === doc ? [doc.documentElement] : [root];
        if (task.ops.length && task.ops[0].name !== 'matches-path' && task.ops[0].name !== 'matches-media') {
          nodes = Array.prototype.slice.call((root === doc ? doc : root).querySelectorAll('*'));
        }
      } else {
        var query = root === doc ? prefix : (/^[>+~]/.test(prefix) ? ':scope ' + prefix : prefix);
        try { nodes = Array.prototype.slice.call((root === doc ? doc : root).querySelectorAll(query)); } catch (e) { return []; }
      }
      return applyOps(task.ops, nodes);
    };

    var filterMatches = function (task, node) {
      if (task.prefix) { try { if (!node.matches(task.prefix)) return false; } catch (e) { return false; } }
      return applyOps(task.ops, [node]).length > 0;
    };

    var applyOps = function (ops, nodes) {
      for (var i = 0; i < ops.length && nodes.length; i++) nodes = applyOp(ops[i], nodes);
      return nodes;
    };

    var unique = function (nodes) { return Array.from(new Set(nodes)); };

    var exactRegExp = function (arg) {
      if (/^\/.+\/[imsu]*$/.test(arg)) return toRegExp(arg);
      var literal = arg.replace(/^(['"])(.*)\1$/, '$2');
      return new RegExp('^' + literal.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$');
    };

    // Everything outside the subjects, their ancestors and their descendants.
    var others = function (nodes) {
      var keep = new Set();
      for (var i = 0; i < nodes.length; i++) {
        for (var el = nodes[i]; el && el !== doc.documentElement; el = el.parentElement) keep.add(el);
      }
      var out = [];
      keep.forEach(function (el) {
        var parent = el.parentElement;
        if (!parent) return;
        for (var sib = parent.firstElementChild; sib; sib = sib.nextElementSibling) {
          if (!keep.has(sib) && KEEP_TAGS[sib.tagName] !== 1) out.push(sib);
        }
      });
      return unique(out);
    };

    // `chain`, `chain=value`, `chain="value"` or `chain=/regex/` on JS properties.
    var propMatcher = function (arg) {
      var m = /^\s*([^=]+?)\s*(?:=\s*(.*?))?\s*$/.exec(arg);
      if (!m) return null;
      var chain = m[1].replace(/^(['"])(.*)\1$/, '$2').split('.');
      var valueRe = m[2] === undefined ? null : exactRegExp(m[2]);
      return function (node) {
        var value = node;
        for (var i = 0; i < chain.length; i++) {
          if (value === undefined || value === null) return false;
          try { value = value[chain[i]]; } catch (e) { return false; }
        }
        return valueRe === null ? value !== undefined : valueRe.test(String(value));
      };
    };

    var applyOp = function (op, nodes) {
      if (op.css !== undefined) {
        // CSS following an operator: compound (".b"), descendant (" .b"),
        // child ("> .b") or sibling ("+ .b", "~ .b") relative to each node.
        var cssText = op.css;
        var trimmed = cssText.trim();
        if (!/^[\s>+~]/.test(cssText)) {
          return nodes.filter(function (n) { try { return n.matches(trimmed); } catch (e) { return false; } });
        }
        var out = [];
        var combinator = trimmed[0];
        var rest = trimmed.slice(1).trim();
        for (var i = 0; i < nodes.length; i++) {
          try {
            if (combinator === '+' || combinator === '~') {
              for (var sib = nodes[i].nextElementSibling; sib; sib = sib.nextElementSibling) {
                if (sib.matches(rest)) out.push(sib);
                if (combinator === '+') break;
              }
            } else {
              out.push.apply(out, nodes[i].querySelectorAll(combinator === '>' ? ':scope > ' + rest : ':scope ' + trimmed));
            }
          } catch (e) { return []; }
        }
        return unique(out);
      }
      var arg = op.arg;
      switch (op.name) {
        case 'has-text': case '-abp-contains': case 'contains': {
          var re = toRegExp(arg);
          return re ? nodes.filter(function (n) { return re.test(n.textContent); }) : [];
        }
        case 'min-text-length': {
          var min = parseInt(arg, 10);
          return nodes.filter(function (n) { return n.textContent.length >= min; });
        }
        case 'upward': case 'nth-ancestor': {
          var count = /^\d+$/.test(arg) ? parseInt(arg, 10) : 0;
          return unique(nodes.map(function (n) {
            if (count > 0) {
              var p = n;
              for (var k = 0; k < count && p; k++) p = p.parentElement;
              return p;
            }
            try { return n.parentElement ? n.parentElement.closest(arg) : null; } catch (e) { return null; }
          }).filter(Boolean));
        }
        case 'xpath': {
          var results = [];
          for (var x = 0; x < nodes.length; x++) {
            try {
              var snap = doc.evaluate(arg, nodes[x], null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
              for (var y = 0; y < snap.snapshotLength; y++) if (snap.snapshotItem(y).nodeType === 1) results.push(snap.snapshotItem(y));
            } catch (e) { return []; }
          }
          return unique(results);
        }
        case 'matches-css': case 'matches-css-before': case 'matches-css-after': {
          var pseudo = op.name === 'matches-css-before' ? '::before' : op.name === 'matches-css-after' ? '::after' : null;
          var colon = arg.indexOf(':');
          if (colon < 0) return [];
          var prop = arg.slice(0, colon).trim();
          var valueRe = toRegExp(arg.slice(colon + 1).trim());
          if (!valueRe) return [];
          var exact = !/^\//.test(arg.slice(colon + 1).trim());
          var wanted = arg.slice(colon + 1).trim();
          return nodes.filter(function (n) {
            var v = getComputedStyle(n, pseudo).getPropertyValue(prop);
            return exact ? v === wanted : valueRe.test(v);
          });
        }
        case 'matches-attr': {
          var am = /^\s*("?)(.+?)\1\s*(?:=\s*("?)(.*?)\3)?\s*$/.exec(arg);
          if (!am) return [];
          var nameRe = toRegExp(am[2]);
          var valRe = am[4] !== undefined ? toRegExp(am[4]) : null;
          return nodes.filter(function (n) {
            for (var a = 0; a < n.attributes.length; a++) {
              var at = n.attributes[a];
              if (nameRe.test(at.name) && (!valRe || valRe.test(at.value))) return true;
            }
            return false;
          });
        }
        case 'matches-path': {
          var pathRe = toRegExp(arg);
          return pathRe && pathRe.test(location.pathname + location.search) ? nodes : [];
        }
        case 'matches-media': {
          try { return matchMedia(arg).matches ? nodes : []; } catch (e) { return []; }
        }
        case 'has': case '-abp-has': case 'if':
          return nodes.filter(function (n) { return select(op.sub, n).length > 0; });
        case 'if-not':
          return nodes.filter(function (n) { return select(op.sub, n).length === 0; });
        case 'not':
          return nodes.filter(function (n) { return !filterMatches(op.sub, n); });
        case 'others':
          return others(nodes);
        case 'shadow': {
          var inShadow = [];
          for (var s = 0; s < nodes.length; s++) {
            var shadowRoot = nodes[s].shadowRoot;
            if (!shadowRoot) continue;
            try { inShadow.push.apply(inShadow, shadowRoot.querySelectorAll(arg)); } catch (e) { return []; }
          }
          return unique(inShadow);
        }
        case 'matches-prop': {
          if (!op.prop) op.prop = propMatcher(arg) || function () { return false; };
          return nodes.filter(op.prop);
        }
        case 'watch-attr':
          // Matching is unaffected; the attributes are observed below.
          return nodes;
      }
      return [];
    };

    var hidden = new WeakSet();
    var apply = function (task, node) {
      var action = task.action;
      if (!action) {
        if (hidden.has(node)) return;
        hidden.add(node);
        node.style.setProperty('display', 'none', 'important');
        return;
      }
      switch (action.name) {
        case 'remove': node.remove(); break;
        case 'style':
          action.arg.split(';').forEach(function (decl) {
            var c = decl.indexOf(':');
            if (c < 0) return;
            var value = decl.slice(c + 1).replace(/!\s*important\s*$/, '').trim();
            node.style.setProperty(decl.slice(0, c).trim(), value, 'important');
          });
          break;
        case 'remove-attr': {
          var re = toRegExp(action.arg);
          Array.prototype.slice.call(node.attributes).forEach(function (at) { if (re && re.test(at.name)) node.removeAttribute(at.name); });
          break;
        }
        case 'remove-class': {
          var cre = toRegExp(action.arg);
          Array.prototype.slice.call(node.classList).forEach(function (c) { if (cre && cre.test(c)) node.classList.remove(c); });
          break;
        }
      }
    };

    var tasks = [];
    // :watch-attr(a, b) re-runs filters when those attributes change; an
    // empty argument watches every attribute.
    var watchedAttrs = [];
    var watchAllAttrs = false;
    var collectWatched = function (ops) {
      for (var k = 0; k < ops.length; k++) {
        var op = ops[k];
        if (op.name === 'watch-attr') {
          var names = op.arg.split(',').map(function (a) { return a.trim().replace(/^(['"])(.*)\1$/, '$2'); }).filter(Boolean);
          if (names.length === 0) watchAllAttrs = true;
          for (var n = 0; n < names.length; n++) if (watchedAttrs.indexOf(names[n]) === -1) watchedAttrs.push(names[n]);
        }
        if (op.sub) collectWatched(op.sub.ops);
      }
    };
    for (var t = 0; t < payload.procedural.length; t++) {
      try {
        var task = parse(payload.procedural[t]);
        if (task) {
          tasks.push(task);
          collectWatched(task.ops);
        }
      } catch (e) { /* malformed filter */ }
    }

    if (tasks.length) {
      var attrObserver = null;
      var run = function () {
        for (var i = 0; i < tasks.length; i++) {
          var nodes;
          try { nodes = select(tasks[i], doc); } catch (e) { continue; }
          for (var j = 0; j < nodes.length; j++) apply(tasks[i], nodes[j]);
        }
        // Our own attribute changes must not re-trigger the watcher.
        if (attrObserver) attrObserver.takeRecords();
      };
      var scheduleRun = throttle(run, 150);
      onReady(function () {
        run();
        observe(scheduleRun);
        if ((watchAllAttrs || watchedAttrs.length) && doc.documentElement) {
          var options = { attributes: true, subtree: true };
          if (!watchAllAttrs) options.attributeFilter = watchedAttrs;
          attrObserver = new MutationObserver(scheduleRun);
          attrObserver.observe(doc.documentElement, options);
        }
      });
    }
  }
})();
