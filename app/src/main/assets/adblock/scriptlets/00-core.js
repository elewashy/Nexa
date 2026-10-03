/*
 * Nexa scriptlet library — shared helpers.
 *
 * The scriptlets below follow the semantics of uBlock Origin's
 * `scriptlets.js` / AdGuard's Scriptlets library: each is a function
 * registered in `S` under its canonical name (see ScriptletCatalog.kt) and
 * invoked with the raw string arguments of the matching filter. Natives are
 * captured here, before any page script runs, so a page cannot tamper with
 * the primitives the scriptlets rely on.
 *
 * Every function the library installs into the page (Proxy wrappers,
 * accessor traps) is registered with `cloak()`; `installToStringCloak()`
 * then makes `Function.prototype.toString` report the original native
 * source for them, so pages cannot detect the patches by stringifying.
 */
var S = Object.create(null);
var H = (function () {
  var win = window;
  var natives = {
    defineProperty: Object.defineProperty,
    getOwnPropertyDescriptor: Object.getOwnPropertyDescriptor,
    getPrototypeOf: Object.getPrototypeOf,
    keys: Object.keys,
    jsonParse: JSON.parse,
    jsonStringify: JSON.stringify,
    RegExp: win.RegExp,
    Error: win.Error,
    Promise: win.Promise,
    WeakMap: win.WeakMap,
    setTimeout: win.setTimeout.bind(win),
    clearTimeout: win.clearTimeout.bind(win),
    fnToString: Function.prototype.toString,
    Proxy: win.Proxy,
    Reflect: win.Reflect
  };

  /** Random identifier used to tag the exceptions our traps throw. */
  var magic = String.fromCharCode(Date.now() % 26 + 97) + Math.floor(Math.random() * 982451653 + 982451653).toString(36);

  /** Runtime options set by `proxy-apply-config`. */
  var config = { skipToString: false };

  // ── Cloaking ───────────────────────────────────────────────────────

  /** Installed function → the function (or literal source) toString() must report. */
  var cloaked = new natives.WeakMap();
  var toStringCloakInstalled = false;

  function cloak(fn, original) {
    if (typeof fn === 'function') cloaked.set(fn, original === undefined ? nativeSource(fn) : original);
    return fn;
  }

  /** Source a native accessor/method with [fn]'s name would print. */
  function nativeSource(fn) {
    var name = '';
    try { name = typeof fn.name === 'string' ? fn.name : ''; } catch (e) { /* ignore */ }
    return 'function ' + name + '() { [native code] }';
  }

  /**
   * Replaces `Function.prototype.toString` with a Proxy that resolves cloaked
   * functions to their originals. Called once, after the scriptlets ran and
   * before any page script, unless a filter opted out (`skipToString`).
   */
  function installToStringCloak() {
    if (toStringCloakInstalled || config.skipToString) return;
    toStringCloakInstalled = true;
    var nativeToString = natives.fnToString;
    var proxy = new natives.Proxy(nativeToString, {
      apply: function (target, thisArg, args) {
        var subject = thisArg;
        for (var depth = 0; depth < 16 && cloaked.has(subject); depth++) {
          var original = cloaked.get(subject);
          if (typeof original === 'string') return original;
          subject = original;
        }
        return natives.Reflect.apply(target, subject, args);
      }
    });
    cloaked.set(proxy, nativeToString);
    try {
      natives.defineProperty(Function.prototype, 'toString', {
        configurable: true, enumerable: false, writable: true, value: proxy
      });
    } catch (e) { /* frozen prototype */ }
  }

  /**
   * `Object.defineProperty` that cloaks the accessors it installs, as native
   * `get <prop>` / `set <prop>` functions. Accessors the caller already
   * cloaked (typically with the native accessor they wrap) keep that mapping.
   */
  function define(owner, prop, desc) {
    var name = typeof prop === 'string' ? prop : '';
    if (typeof desc.get === 'function' && !cloaked.has(desc.get)) {
      cloak(desc.get, 'function get ' + name + '() { [native code] }');
    }
    if (typeof desc.set === 'function' && !cloaked.has(desc.set)) {
      cloak(desc.set, 'function set ' + name + '() { [native code] }');
    }
    natives.defineProperty(owner, prop, desc);
  }

  // ── Errors ─────────────────────────────────────────────────────────

  var errorHandlerInstalled = false;
  /**
   * Swallows the errors thrown by abort-* traps so they don't surface as page
   * errors. A listener (registered before any page script) is used instead
   * of `window.onerror`, which pages can read back.
   */
  function installErrorHandler() {
    if (errorHandlerInstalled) return;
    errorHandlerInstalled = true;
    win.addEventListener('error', function (ev) {
      var msg = ev.message;
      if (typeof msg !== 'string' || msg.indexOf(magic) === -1) {
        var err = ev.error;
        msg = err && typeof err.message === 'string' ? err.message : String(err);
      }
      if (msg.indexOf(magic) === -1) return;
      ev.preventDefault();
      ev.stopImmediatePropagation();
    }, true);
  }

  /**
   * Error thrown by abort traps. Scriptlets call [installErrorHandler] when
   * they are set up, so the listener runs before any page listener; the call
   * here is only a safety net.
   */
  function abortError() {
    installErrorHandler();
    return new ReferenceError(magic);
  }

  // ── Patterns ───────────────────────────────────────────────────────

  function escapeRegex(s) {
    return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }

  /**
   * uBO pattern syntax: `/regex/flags` is a regular expression, anything else
   * a literal substring; an empty pattern matches everything. With
   * `canNegate`, a leading `!` inverts the test.
   */
  function matcher(pattern, canNegate) {
    var p = pattern === undefined || pattern === null ? '' : String(pattern);
    var negate = false;
    if (canNegate && p.charAt(0) === '!') {
      negate = true;
      p = p.slice(1);
    }
    var re;
    var m = /^\/(.+)\/([gimsu]*)$/.exec(p);
    if (m) {
      try { re = new natives.RegExp(m[1], m[2].replace('g', '')); } catch (e) { re = /(?!)/; }
    } else if (p === '') {
      re = /^/;
    } else {
      re = new natives.RegExp(escapeRegex(p));
    }
    return {
      empty: p === '',
      test: function (s) { return re.test(String(s)) !== negate; }
    };
  }

  /** `/re/flags` → RegExp (with [flags] added), literal → escaped RegExp, '*' → whole text. */
  function toRegExp(pattern, flags) {
    if (pattern === '*') return new natives.RegExp('^[\\s\\S]*$', flags);
    var m = /^\/(.+)\/([dgimsuy]*)$/.exec(pattern || '');
    var source = m ? m[1] : escapeRegex(pattern || '');
    var allFlags = (m ? m[2] : '') + (flags || '');
    var unique = '';
    for (var i = 0; i < allFlags.length; i++) if (unique.indexOf(allFlags[i]) === -1) unique += allFlags[i];
    try { return new natives.RegExp(source, unique); } catch (e) { return null; }
  }

  /** Named trailing arguments: `..., key, value, key2, value2`. */
  function extraArgs(args, start) {
    var out = {};
    for (var i = start; i + 1 < args.length; i += 2) out[args[i]] = args[i + 1];
    return out;
  }

  var noopFunc = function () {};
  var SKIP = {};

  /**
   * Converts a scriptlet value token to a JS value (set-constant and
   * friends). Untrusted lists are limited to this vocabulary; trusted ones may
   * pass JSON via [trusted].
   */
  function constantValue(raw, trusted) {
    switch (raw) {
      case 'undefined': return undefined;
      case 'false': return false;
      case 'true': return true;
      case 'null': return null;
      case "''": case '': case 'emptyStr': return '';
      case '[]': case 'emptyArr': return [];
      case '{}': case 'emptyObj': return {};
      case 'noopFunc': return noopFunc;
      case 'noopCallbackFunc': return function () { return noopFunc; };
      case 'trueFunc': return function () { return true; };
      case 'falseFunc': return function () { return false; };
      case 'throwFunc':
        installErrorHandler();
        return function () { throw abortError(); };
      case 'yes': case 'no': case 'on': case 'off': case 'Y': case 'N': case 'accept': case 'reject':
        return raw;
      case 'NaN': return NaN;
      case 'Infinity': return Infinity;
      case '-1': return -1;
    }
    if (/^\d+$/.test(raw)) {
      var n = parseInt(raw, 10);
      return n <= 0x7FFF || trusted ? n : SKIP;
    }
    if (trusted) {
      try { return natives.jsonParse(raw); } catch (e) { return raw; }
    }
    return SKIP;
  }

  // ── Property chains ────────────────────────────────────────────────

  /**
   * Walks a dotted property [chain] starting at [owner]. When an intermediate
   * object does not exist yet, a setter trap waits for it to be assigned and
   * resumes the walk; [onLeaf] receives (owner, prop) for the final segment.
   */
  function trapChain(owner, chain, onLeaf) {
    var pos = chain.indexOf('.');
    if (pos === -1) {
      onLeaf(owner, chain);
      return;
    }
    var prop = chain.slice(0, pos);
    var rest = chain.slice(pos + 1);
    var current;
    try { current = owner[prop]; } catch (e) { return; }
    if (current !== undefined && current !== null && (typeof current === 'object' || typeof current === 'function')) {
      trapChain(current, rest, onLeaf);
      return;
    }
    var desc = natives.getOwnPropertyDescriptor(owner, prop);
    if (desc && desc.configurable === false) return;
    var value = current;
    try {
      define(owner, prop, {
        configurable: true,
        enumerable: desc ? desc.enumerable : true,
        get: function () { return value; },
        set: function (v) {
          value = v;
          if (v !== undefined && v !== null && (typeof v === 'object' || typeof v === 'function')) trapChain(v, rest, onLeaf);
        }
      });
    } catch (e) { /* non-extensible owner */ }
  }

  /** Descriptor of [prop] on [owner] or the nearest prototype defining it. */
  function lookupDescriptor(owner, prop) {
    for (var o = owner; o !== null && o !== undefined; o = natives.getPrototypeOf(o)) {
      var desc = natives.getOwnPropertyDescriptor(o, prop);
      if (desc) return desc;
    }
    return undefined;
  }

  /**
   * Installs an own accessor for [prop] on [owner] that calls [guard] (which
   * throws to abort) on every read and write, and otherwise behaves like the
   * property it shadows. Inherited members count: `window.addEventListener`
   * and `document.documentElement` live on prototypes, and shadowing them
   * with `undefined` would break every script on the page, not only the one
   * being aborted. Inherited accessors are delegated to with the original
   * receiver; data properties keep their value in the trap.
   */
  function trapAccess(owner, prop, guard) {
    var own = natives.getOwnPropertyDescriptor(owner, prop);
    if (own && own.configurable === false) return;
    var desc = own || lookupDescriptor(owner, prop);
    var isAccessor = desc !== undefined && ('get' in desc || 'set' in desc);
    var getter = isAccessor ? desc.get : undefined;
    var setter = isAccessor ? desc.set : undefined;
    var value = desc !== undefined && !isAccessor ? desc.value : undefined;
    try {
      define(owner, prop, {
        configurable: true,
        enumerable: desc ? desc.enumerable : true,
        get: function () {
          guard();
          if (!isAccessor) return value;
          return getter ? getter.call(this) : undefined;
        },
        set: function (v) {
          guard();
          if (!isAccessor) value = v;
          else if (setter) setter.call(this, v);
        }
      });
    } catch (e) { /* non-extensible owner */ }
  }

  /** Resolves `a.b.c` to { owner: a.b, prop: 'c' } from window, or null when a link is missing. */
  function resolveChain(chain) {
    if (!chain) return null;
    var parts = chain.split('.');
    var owner = win;
    for (var i = 0; i < parts.length - 1; i++) {
      try { owner = owner[parts[i]]; } catch (e) { return null; }
      if (owner === undefined || owner === null) return null;
    }
    return { owner: owner, prop: parts[parts.length - 1] };
  }

  /**
   * uBO `proxyApplyFn`: replaces the function at [chain] with a Proxy whose
   * apply trap calls [handler](context). `context.reflect()` performs the
   * original call; the handler's return value is the call's result.
   */
  function proxyApply(chain, handler) {
    var where = resolveChain(chain);
    if (!where) return false;
    var original;
    try { original = where.owner[where.prop]; } catch (e) { return false; }
    if (typeof original !== 'function') return false;
    var proxy = new natives.Proxy(original, {
      apply: function (target, thisArg, args) {
        return handler({
          callArgs: args,
          thisArg: thisArg,
          reflect: function () { return natives.Reflect.apply(target, thisArg, args); }
        });
      }
    });
    cloak(proxy, original);
    try {
      var desc = natives.getOwnPropertyDescriptor(where.owner, where.prop);
      if (desc && desc.writable === false && desc.configurable) {
        natives.defineProperty(where.owner, where.prop, {
          configurable: true, enumerable: desc.enumerable, writable: false, value: proxy
        });
      } else {
        where.owner[where.prop] = proxy;
      }
    } catch (e) { return false; }
    return true;
  }

  /** Wraps `owner[name]` with a Proxy apply trap, preserving toString(). */
  function wrapFunction(owner, name, apply) {
    var original = owner && owner[name];
    if (typeof original !== 'function') return;
    try {
      var proxy = new natives.Proxy(original, {
        apply: function (target, thisArg, args) { return apply(target, thisArg, args); }
      });
      cloak(proxy, original);
      owner[name] = proxy;
    } catch (e) { /* frozen */ }
  }

  function fnText(fn) {
    if (typeof fn === 'function') {
      try { return natives.fnToString.call(fn); } catch (e) { return ''; }
    }
    return String(fn);
  }

  /**
   * uBO `matchesStackTraceFn`: tests [needle] against the current call stack
   * normalised to tab-separated `function url:line:1` entries, where the
   * document's own inline scripts read `inlineScript` and eval'd code
   * `injectedScript`. The first entry is `stackDepth:N`.
   */
  function matchesStack(needle) {
    var stack = '';
    try { stack = new natives.Error(magic).stack || ''; } catch (e) { return false; }
    var docUrl = String(location.href).replace(/#.*$/, '');
    var reLine = /(.*?@)?(\S+)(:\d+):\d+\)?$/;
    var lines = [];
    var raw = stack.split(/[\n\r]+/);
    for (var i = 0; i < raw.length; i++) {
      var line = raw[i];
      if (line.indexOf(magic) !== -1) continue;
      line = line.trim();
      var m = reLine.exec(line);
      if (m === null) continue;
      var url = m[2];
      if (url.charAt(0) === '(') url = url.slice(1);
      if (url === docUrl) url = 'inlineScript';
      else if (url.indexOf('<anonymous>') === 0) url = 'injectedScript';
      var fn = m[1] !== undefined ? m[1].slice(0, -1) : line.slice(0, m.index).trim();
      if (fn.indexOf('at') === 0) fn = fn.slice(2).trim();
      lines.push(' ' + (fn + ' ' + url + m[3] + ':1').trim());
    }
    lines[0] = 'stackDepth:' + (lines.length - 1);
    return needle.test(lines.join('\t'));
  }

  // ── DOM scheduling ─────────────────────────────────────────────────

  /** Text of a script element (inline text, or its src for external scripts). */
  function scriptText(el) {
    if (!el) return '';
    var text = el.textContent || '';
    if (text.trim() === '' && el.src) text = el.src;
    return text;
  }

  function onDomReady(fn) {
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn, { once: true });
    else fn();
  }

  /**
   * Runs [fn] now, at DOMContentLoaded/load per [when] (`asap`, `interactive`,
   * `complete`), and on later DOM mutations when [observe] is set.
   */
  function runAt(fn, when, observe) {
    var run = function () { try { fn(); } catch (e) { /* ignore */ } };
    var startObserver = function () {
      if (!observe || !document.documentElement) return;
      var pending = false;
      new MutationObserver(function () {
        if (pending) return;
        pending = true;
        natives.setTimeout(function () { pending = false; run(); }, 0);
      }).observe(document.documentElement, { childList: true, subtree: true });
    };
    if (when === 'complete') {
      if (document.readyState === 'complete') { run(); startObserver(); }
      else win.addEventListener('load', function () { run(); startObserver(); }, { once: true });
      return;
    }
    if (when === 'interactive') {
      onDomReady(function () { run(); startObserver(); });
      return;
    }
    run();
    if (document.documentElement) startObserver(); else onDomReady(startObserver);
  }

  // ── Request matching ───────────────────────────────────────────────

  var REQUEST_PROPS = {
    url: 1, method: 1, body: 1, mode: 1, credentials: 1, cache: 1, redirect: 1, referrer: 1,
    referrerPolicy: 1, integrity: 1
  };

  /** Parses `key:value key2:value2` property filters used by no-fetch-if / no-xhr-if. */
  function parsePropsToMatch(raw) {
    var out = [];
    if (!raw) return out;
    var parts = String(raw).split(/\s+/);
    for (var i = 0; i < parts.length; i++) {
      var part = parts[i];
      if (!part) continue;
      var colon = part.indexOf(':');
      var key = 'url';
      var value = part;
      if (colon > 0 && REQUEST_PROPS[part.slice(0, colon)] === 1) {
        key = part.slice(0, colon);
        value = part.slice(colon + 1);
      }
      out.push({ key: key, m: matcher(value === '*' ? '' : value, true) });
    }
    return out;
  }

  function matchesProps(props, details) {
    for (var i = 0; i < props.length; i++) {
      var v = details[props[i].key];
      if (v === undefined || !props[i].m.test(v)) return false;
    }
    return true;
  }

  return {
    natives: natives,
    magic: magic,
    config: config,
    noopFunc: noopFunc,
    SKIP: SKIP,
    cloak: cloak,
    define: define,
    installToStringCloak: installToStringCloak,
    installErrorHandler: installErrorHandler,
    abortError: abortError,
    escapeRegex: escapeRegex,
    matcher: matcher,
    toRegExp: toRegExp,
    extraArgs: extraArgs,
    constantValue: constantValue,
    trapChain: trapChain,
    trapAccess: trapAccess,
    resolveChain: resolveChain,
    proxyApply: proxyApply,
    wrapFunction: wrapFunction,
    fnText: fnText,
    matchesStack: matchesStack,
    scriptText: scriptText,
    onDomReady: onDomReady,
    runAt: runAt,
    parsePropsToMatch: parsePropsToMatch,
    matchesProps: matchesProps
  };
})();
