/* Native-method overrides, DOM helpers, navigation/UI defusers and injectable resources. */

// ── Native methods ─────────────────────────────────────────────────────

/** `"pattern"` | `/regex/` | `{json}` | `true`/`false`/`null`/`undefined` | number | literal. */
function argumentMatcher(raw) {
  if (raw === '') return null;
  if (/^".*"$/.test(raw)) {
    var quoted = H.matcher(raw.slice(1, -1), false);
    return function (v) { return quoted.test(v); };
  }
  if (/^\/.+\/[gimsu]*$/.test(raw)) {
    var re = H.matcher(raw, false);
    return function (v) { return re.test(v); };
  }
  if (/^\{.*\}$/.test(raw)) {
    var props;
    try { props = H.natives.jsonParse(raw); } catch (e) { props = null; }
    if (props !== null && typeof props === 'object') {
      var keys = H.natives.keys(props);
      return function (v) {
        if (v === null || (typeof v !== 'object' && typeof v !== 'function')) return false;
        for (var i = 0; i < keys.length; i++) if (v[keys[i]] !== props[keys[i]]) return false;
        return true;
      };
    }
  }
  var exact;
  switch (raw) {
    case 'true': exact = true; break;
    case 'false': exact = false; break;
    case 'null': exact = null; break;
    case 'undefined': exact = undefined; break;
    default: exact = /^-?\d+(\.\d+)?$/.test(raw) ? Number(raw) : raw;
  }
  return function (v) { return v === exact; };
}

/**
 * trusted-suppress-native-method(fn, signature, how, stack): calls to [fn]
 * whose arguments match the `|`-separated [signature] (and whose stack
 * matches [stack]) are dropped (`how` = `prevent`/empty) or throw (`abort`).
 */
S['trusted-suppress-native-method'] = function (chain, signature, how, stack) {
  if (!chain || !signature) return;
  var matchers = String(signature).split(/\s*\|\s*/).map(argumentMatcher);
  var stackNeedle = stack ? H.matcher(stack, true) : null;
  if (how === 'abort') H.installErrorHandler();
  H.proxyApply(chain, function (ctx) {
    var args = ctx.callArgs;
    for (var i = 0; i < matchers.length; i++) {
      if (matchers[i] !== null && !matchers[i](args[i])) return ctx.reflect();
    }
    if (stackNeedle !== null && !H.matchesStack(stackNeedle)) return ctx.reflect();
    if (how === 'abort') throw H.abortError();
    return undefined;
  });
};

/**
 * trusted-override-element-method(fn, selector, disposition): calls to the
 * element method [fn] on elements matching [selector] do nothing, return the
 * [disposition] value, or throw (`throw`).
 */
S['trusted-override-element-method'] = function (chain, selector, disposition) {
  if (!chain) return;
  var result = disposition ? H.constantValue(disposition, true) : undefined;
  if (result === H.SKIP) result = undefined;
  if (disposition === 'throw') H.installErrorHandler();
  H.proxyApply(chain, function (ctx) {
    var override = !selector;
    if (!override) {
      try { override = typeof ctx.thisArg.matches === 'function' && ctx.thisArg.matches(selector); } catch (e) { override = false; }
    }
    if (!override) return ctx.reflect();
    if (disposition === 'throw') throw H.abortError();
    return result;
  });
};

/**
 * trusted-replace-outbound-text(fn, pattern, replacement[, condition, re,
 * encoding, base64]): rewrites the string returned by [fn].
 */
S['trusted-replace-outbound-text'] = function (chain, rawPattern, rawReplacement) {
  if (!chain || !rawPattern) return;
  var extra = H.extraArgs(arguments, 3);
  var re = H.toRegExp(rawPattern, '');
  if (!re) return;
  var replacement = rawReplacement || '';
  if (replacement.indexOf('json:') === 0) {
    try { replacement = String(H.natives.jsonParse(replacement.slice(5))); } catch (e) { return; }
  }
  var condition = H.matcher(extra.condition || '', false);
  var base64 = extra.encoding === 'base64';
  H.proxyApply(chain, function (ctx) {
    var encoded = ctx.reflect();
    var text = encoded;
    if (base64) {
      try { text = atob(encoded); } catch (e) { return encoded; }
    }
    if (typeof text !== 'string' || !condition.test(text)) return encoded;
    var after = text.replace(re, replacement);
    if (after === text) return encoded;
    return base64 ? btoa(after) : after;
  });
};

/**
 * trusted-prevent-dom-bypass(fn, prop): when [fn] (appendChild & co.)
 * inserts a same-origin frame, the frame's [prop] is replaced with ours (or,
 * without [prop], its `contentWindow` becomes this window), so pages cannot
 * fetch pristine natives from a fresh iframe.
 */
S['trusted-prevent-dom-bypass'] = function (chain, targetProp) {
  if (!chain) return;
  H.proxyApply(chain, function (ctx) {
    var elems = [];
    for (var i = 0; i < ctx.callArgs.length; i++) {
      if (typeof HTMLElement === 'function' && ctx.callArgs[i] instanceof HTMLElement) elems.push(ctx.callArgs[i]);
    }
    var result = ctx.reflect();
    for (var j = 0; j < elems.length; j++) {
      try {
        var frameWindow = elems[j].contentWindow;
        if (String(frameWindow) !== '[object Window]') continue;
        var href = frameWindow.location.href;
        if (href !== 'about:blank' && href !== location.href) continue;
        if (!targetProp) {
          H.natives.defineProperty(elems[j], 'contentWindow', { value: window });
          continue;
        }
        var parts = targetProp.split('.');
        var mine = window;
        var theirs = frameWindow;
        for (var k = 0; k < parts.length - 1; k++) {
          mine = mine[parts[k]];
          theirs = theirs[parts[k]];
        }
        theirs[parts[parts.length - 1]] = mine[parts[parts.length - 1]];
      } catch (e) { /* cross-origin or read-only */ }
    }
    return result;
  });
};

// ── CSS / DOM ──────────────────────────────────────────────────────────

/**
 * spoof-css(selector, prop, value, …): getComputedStyle() and
 * getBoundingClientRect() report the given values for matching elements, so
 * pages cannot detect that cosmetic filters hid them. `_rectx`, `_recty`,
 * `_rectw`, `_recth` override the rectangle.
 */
S['spoof-css'] = function (selector) {
  if (!selector) return;
  var camel = function (s) { return String(s).replace(/-[a-z]/g, function (m) { return m.charAt(1).toUpperCase(); }); };
  var spoofed = Object.create(null);
  var rect = Object.create(null);
  for (var i = 1; i + 1 < arguments.length; i += 2) {
    var prop = camel(arguments[i]);
    if (prop === '') break;
    if (prop.charAt(0) === '_') rect[prop] = parseFloat(arguments[i + 1]);
    else spoofed[prop] = String(arguments[i + 1]);
  }
  var isTarget = function (el) {
    try { return typeof el.matches === 'function' && el.matches(selector); } catch (e) { return false; }
  };
  var spoof = function (prop, real) {
    var key = camel(prop);
    return key in spoofed ? spoofed[key] : real;
  };
  var STYLE_INTERNALS = { cssText: 1, length: 1, parentRule: 1 };
  H.proxyApply('getComputedStyle', function (ctx) {
    var style = ctx.reflect();
    if (!isTarget(ctx.callArgs[0])) return style;
    return new H.natives.Proxy(style, {
      get: function (target, prop) {
        var value = target[prop];
        if (typeof value === 'function') {
          var bound = prop === 'getPropertyValue'
            ? function getPropertyValue(name) { return spoof(name, target.getPropertyValue(name)); }
            : value.bind(target);
          return H.cloak(bound, value);
        }
        if (typeof prop !== 'string' || STYLE_INTERNALS[prop] === 1) return value;
        return spoof(prop, value);
      },
      getOwnPropertyDescriptor: function (target, prop) {
        if (typeof prop === 'string' && prop in spoofed) {
          return { configurable: true, enumerable: true, writable: true, value: spoofed[prop] };
        }
        return H.natives.Reflect.getOwnPropertyDescriptor(target, prop);
      }
    });
  });
  H.proxyApply('Element.prototype.getBoundingClientRect', function (ctx) {
    var r = ctx.reflect();
    if (!isTarget(ctx.thisArg) || typeof DOMRect !== 'function') return r;
    var width = '_rectw' in rect ? rect._rectw : 'width' in spoofed ? parseFloat(spoofed.width) : r.width;
    var height = '_recth' in rect ? rect._recth : 'height' in spoofed ? parseFloat(spoofed.height) : r.height;
    return new DOMRect('_rectx' in rect ? rect._rectx : r.x, '_recty' in rect ? rect._recty : r.y, width, height);
  });
};

/** prevent-innerHTML(selector, pattern): ignores `innerHTML` writes matching [pattern] on [selector] elements. */
S['prevent-innerHTML'] = function (selector, pattern) {
  var proto = typeof Element === 'function' ? Element.prototype : null;
  var desc = proto && H.natives.getOwnPropertyDescriptor(proto, 'innerHTML');
  if (!desc || typeof desc.set !== 'function') return;
  var m = H.matcher(pattern || '', true);
  var prevent = function (el, value) {
    if (selector) {
      try { if (!el.matches(selector)) return false; } catch (e) { return false; }
    }
    return m.test(value);
  };
  H.define(proto, 'innerHTML', {
    configurable: true,
    enumerable: desc.enumerable,
    get: H.cloak(function () { return desc.get.call(this); }, desc.get),
    set: H.cloak(function (value) {
      if (!prevent(this, value)) desc.set.call(this, value);
    }, desc.set)
  });
};

/**
 * trusted-create-html(parentSelector, html, durationMs): appends [html] to
 * the first element matching [parentSelector] once it exists, optionally
 * removing it again after [durationMs].
 */
var CREATE_HTML_MARK = typeof Symbol === 'function' ? Symbol.for('nexa.trustedCreateHTML') : '__nexaTrustedCreateHTML';
S['trusted-create-html'] = function (parentSelector, html, durationMs) {
  if (!parentSelector || !html || typeof DOMParser !== 'function') return;
  // Never recurse into frames this scriptlet created further up.
  try {
    for (var w = window; w; w = w.parent === w ? null : w.parent) {
      if (w[CREATE_HTML_MARK]) return;
    }
  } catch (e) { /* cross-origin ancestor */ }
  try { H.natives.defineProperty(window, CREATE_HTML_MARK, { value: true }); } catch (e) { /* ignore */ }
  var duration = parseInt(durationMs, 10);
  var parsed = new DOMParser().parseFromString(html, 'text/html');
  var fragment = document.createDocumentFragment();
  var created = [];
  while (parsed.body && parsed.body.firstChild) {
    var node = document.adoptNode(parsed.body.firstChild);
    fragment.appendChild(node);
    created.push(node);
  }
  if (!fragment.firstChild) return;
  var append = function () {
    var parent;
    try { parent = document.querySelector(parentSelector); } catch (e) { return true; }
    if (!parent) return false;
    parent.append(fragment);
    if (!isNaN(duration)) {
      H.natives.setTimeout(function () {
        for (var i = 0; i < created.length; i++) if (created[i].parentNode) created[i].parentNode.removeChild(created[i]);
      }, duration);
    }
    return true;
  };
  if (append()) return;
  var observer = new MutationObserver(function () { if (append()) observer.disconnect(); });
  observer.observe(document, { childList: true, subtree: true });
};

// ── Navigation / UI ────────────────────────────────────────────────────

/** prevent-navigation(urlPattern): cancels script-initiated navigations to matching URLs. */
S['prevent-navigation'] = function (urlPattern) {
  var nav = window.navigation;
  if (!nav || typeof nav.addEventListener !== 'function') return;
  var m = H.matcher(urlPattern === 'location.href' ? location.href : (urlPattern || ''), true);
  nav.addEventListener('navigate', function (ev) {
    if (ev.userInitiated || !ev.cancelable || !ev.destination) return;
    if (m.test(ev.destination.url)) ev.preventDefault();
  });
};

/** window-close-if(pattern): closes the window when its URL (regex) or path+query (literal) matches. */
S['window-close-if'] = function (pattern) {
  var arg = pattern || '';
  var subject = '';
  if (/^\/.*\/[a-z]*$/.test(arg)) subject = location.href;
  else if (arg !== '') subject = location.pathname + location.search;
  if (H.matcher(arg, false).test(subject)) window.close();
};

S['alert-buster'] = function () {
  H.proxyApply('alert', function () { return undefined; });
};

/** prevent-canvas(contextType): getContext() returns null for matching context types. */
S['prevent-canvas'] = function (contextType) {
  var m = H.matcher(contextType || '', true);
  H.proxyApply('HTMLCanvasElement.prototype.getContext', function (ctx) {
    return m.test(ctx.callArgs[0]) ? null : ctx.reflect();
  });
};

/** Non-blocking notice shown by prevent-clipboard-write's `domAlert`. */
function showDomAlert(message) {
  var show = function () {
    var root = document.body || document.documentElement;
    if (!root) return;
    var box = document.createElement('div');
    box.setAttribute('role', 'alert');
    box.textContent = message;
    box.style.cssText = 'position:fixed;left:8px;right:8px;top:8px;z-index:2147483647;padding:12px 16px;' +
      'background:#b00020;color:#fff;font:14px/1.4 sans-serif;border-radius:8px;box-shadow:0 2px 8px rgba(0,0,0,.4);' +
      'white-space:pre-wrap;word-break:break-word;max-height:40vh;overflow:auto';
    box.addEventListener('click', function () { box.remove(); });
    root.appendChild(box);
    H.natives.setTimeout(function () { box.remove(); }, 15000);
  };
  H.onDomReady(show);
}

/**
 * prevent-clipboard-write(pattern[, excludeMatches, re, domAlert, message]):
 * blocks clipboard writes whose text matches [pattern] (ClickFix-style
 * "paste this command" attacks) through every write path pages use.
 */
S['prevent-clipboard-write'] = function (pattern) {
  var extra = H.extraArgs(arguments, 1);
  var m = H.matcher(pattern || '', true);
  var exclude = extra.excludeMatches ? H.matcher(extra.excludeMatches, false) : null;
  var lastAlert = '';
  var blocked = function (text) {
    if (typeof text !== 'string' || text === '') return false;
    if ((exclude && exclude.test(text)) || !m.test(text)) return false;
    if (extra.domAlert && text !== lastAlert) {
      lastAlert = text;
      showDomAlert(String(extra.domAlert).replace('${text}', text.length > 300 ? text.slice(0, 300) + '…' : text));
    }
    return true;
  };
  H.proxyApply('Clipboard.prototype.writeText', function (ctx) {
    return blocked(String(ctx.callArgs[0])) ? H.natives.Promise.resolve() : ctx.reflect();
  });
  H.proxyApply('Clipboard.prototype.write', function (ctx) {
    var items = ctx.callArgs[0];
    var texts = [];
    if (items && typeof items.length === 'number') {
      for (var i = 0; i < items.length; i++) {
        var item = items[i];
        if (item && item.types && Array.prototype.indexOf.call(item.types, 'text/plain') !== -1) {
          texts.push(item.getType('text/plain').then(function (blob) { return blob.text(); }));
        }
      }
    }
    if (texts.length === 0) return ctx.reflect();
    return H.natives.Promise.all(texts).then(function (list) {
      for (var j = 0; j < list.length; j++) if (blocked(list[j])) return undefined;
      return ctx.reflect();
    });
  });
  H.proxyApply('DataTransfer.prototype.setData', function (ctx) {
    var format = String(ctx.callArgs[0]).toLowerCase();
    if ((format === 'text' || format === 'text/plain') && blocked(String(ctx.callArgs[1]))) return undefined;
    return ctx.reflect();
  });
  H.proxyApply('Document.prototype.execCommand', function (ctx) {
    if (String(ctx.callArgs[0]).toLowerCase() !== 'copy') return ctx.reflect();
    var text = '';
    var el = document.activeElement;
    if (el && (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') && typeof el.selectionStart === 'number') {
      text = el.value.slice(el.selectionStart, el.selectionEnd);
    } else if (typeof window.getSelection === 'function') {
      text = String(window.getSelection());
    }
    return blocked(text) ? false : ctx.reflect();
  });
};

/** proxy-apply-config(json): `{"skipToString":true}` keeps Function.prototype.toString untouched on this site. */
S['proxy-apply-config'] = function (raw) {
  var parsed;
  try { parsed = H.natives.jsonParse(raw || '{}'); } catch (e) { return; }
  if (parsed && parsed.skipToString === true) H.config.skipToString = true;
};

// ── Injectable resources (uBO `popads.net.js`, `fingerprint2.js`, `multiup.js`) ──

S['popads.net'] = function () {
  H.installErrorHandler();
  var thrower = function () { throw H.abortError(); };
  var names = ['PopAds', 'popns'];
  for (var i = 0; i < names.length; i++) {
    try {
      delete window[names[i]];
      H.define(window, names[i], { configurable: true, get: function () { return undefined; }, set: thrower });
    } catch (e) { /* ignore */ }
  }
};

S['fingerprint2'] = function () {
  var browserId = '';
  for (var i = 0; i < 8; i++) browserId += (Math.random() * 0x10000 + 0x1000 | 0).toString(16).slice(-4);
  var Fingerprint2 = function () {};
  Fingerprint2.get = function (opts, cb) {
    var callback = typeof opts === 'function' ? opts : cb;
    H.natives.setTimeout(function () { if (typeof callback === 'function') callback(browserId, []); }, 1);
  };
  Fingerprint2.prototype = { get: Fingerprint2.get };
  window.Fingerprint2 = Fingerprint2;
};

/** multiup: download buttons navigate straight to their `link` instead of the ad-laden form flow. */
S['multiup'] = function () {
  document.addEventListener('click', function (ev) {
    var target = ev.target;
    if (!target || typeof target.matches !== 'function' || !target.matches('button[link]')) return;
    var form = target.closest('form');
    if (form === null || form !== target.parentElement) return;
    var link = (target.getAttribute('link') || '').trim();
    if (link === '') return;
    ev.preventDefault();
    ev.stopPropagation();
    document.location.href = link;
  }, { capture: true });
};
