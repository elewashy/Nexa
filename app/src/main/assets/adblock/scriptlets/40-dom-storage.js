/* DOM, cookie and storage scriptlets. */

/**
 * Watches nodes named [nodeName] (`script`, `#text`, …) as they are inserted
 * and passes them to [handler]; existing nodes are processed immediately.
 */
function watchNodes(nodeName, handler) {
  var wanted = H.matcher(nodeName || '', false);
  var visit = function (node) {
    if (wanted.test(node.nodeName.toLowerCase())) {
      try { handler(node); } catch (e) { /* ignore */ }
    }
  };
  var scanExisting = function () {
    var walker = document.createTreeWalker(document.documentElement || document, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
    for (var n = walker.currentNode; n; n = walker.nextNode()) visit(n);
  };
  var observer = new MutationObserver(function (mutations) {
    for (var i = 0; i < mutations.length; i++) {
      var added = mutations[i].addedNodes;
      for (var j = 0; j < added.length; j++) {
        visit(added[j]);
        if (added[j].nodeType === 1 && added[j].firstChild) {
          var walker = document.createTreeWalker(added[j], NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
          for (var n = walker.nextNode(); n; n = walker.nextNode()) visit(n);
        }
      }
    }
  });
  var start = function () {
    if (!document.documentElement) return;
    scanExisting();
    observer.observe(document.documentElement, { childList: true, subtree: true });
  };
  if (document.documentElement) start(); else H.onDomReady(start);
  // Parsing is over at load; stop paying for mutation callbacks soon after.
  window.addEventListener('load', function () {
    H.natives.setTimeout(function () { observer.disconnect(); }, 5000);
  }, { once: true });
}

S['remove-node-text'] = function (nodeName, condition) {
  if (!nodeName) return;
  var m = H.matcher(condition || '', true);
  watchNodes(nodeName, function (node) {
    if (m.test(node.textContent)) node.textContent = '';
  });
};

S['replace-node-text'] = function (nodeName, pattern, replacement) {
  if (!nodeName || !pattern) return;
  var extra = H.extraArgs(arguments, 3);
  var re = H.toRegExp(pattern, 'g');
  if (!re) return;
  var condition = H.matcher(extra.condition || '', true);
  watchNodes(nodeName, function (node) {
    var text = node.textContent;
    if (!condition.test(text)) return;
    var replaced = text.replace(re, replacement || '');
    if (replaced !== text) node.textContent = replaced;
  });
};

function forEachMatch(selector, fn) {
  var nodes;
  try { nodes = document.querySelectorAll(selector); } catch (e) { return; }
  for (var i = 0; i < nodes.length; i++) fn(nodes[i]);
}

S['remove-attr'] = function (rawAttrs, selector, behavior) {
  if (!rawAttrs) return;
  var attrs = rawAttrs.split(/\s*\|\s*/).filter(Boolean);
  var sel = selector || attrs.map(function (a) { return '[' + a + ']'; }).join(',');
  var run = function () {
    forEachMatch(sel, function (el) {
      for (var i = 0; i < attrs.length; i++) el.removeAttribute(attrs[i]);
    });
  };
  var when = /complete/.test(behavior || '') ? 'complete' : /asap/.test(behavior || '') ? 'asap' : 'interactive';
  H.runAt(run, when, /stay/.test(behavior || ''));
};

S['remove-class'] = function (rawClasses, selector, behavior) {
  if (!rawClasses) return;
  var classes = rawClasses.split(/\s*\|\s*/).filter(Boolean);
  var sel = selector || classes.map(function (c) { return '.' + CSS.escape(c); }).join(',');
  var run = function () {
    forEachMatch(sel, function (el) {
      for (var i = 0; i < classes.length; i++) el.classList.remove(classes[i]);
    });
  };
  var when = /complete/.test(behavior || '') ? 'complete' : /asap/.test(behavior || '') ? 'asap' : 'interactive';
  H.runAt(run, when, /stay/.test(behavior || ''));
};

function setAttrImpl(selector, attr, value, trusted) {
  if (!selector || !attr) return;
  if (/^on/i.test(attr)) return; // never create event handler attributes
  var copyFrom = /^\[([^\]]+)\]$/.exec(value || '');
  if (!trusted && !copyFrom && value !== '' && value !== undefined &&
      value !== 'true' && value !== 'false' && !/^\d+$/.test(value)) {
    return;
  }
  var run = function () {
    forEachMatch(selector, function (el) {
      var v = copyFrom ? el.getAttribute(copyFrom[1]) : (value || '');
      if (v !== null && el.getAttribute(attr) !== v) el.setAttribute(attr, v);
    });
  };
  H.runAt(run, 'interactive', true);
}

S['set-attr'] = function (selector, attr, value) { setAttrImpl(selector, attr, value, false); };
S['trusted-set-attr'] = function (selector, attr, value) { setAttrImpl(selector, attr, value, true); };

/** Values untrusted lists may write into cookies / storage. */
function safeStorageValue(raw) {
  var allowed = {
    'true': 1, 'false': 1, 'yes': 1, 'no': 1, 'y': 1, 'n': 1, 'ok': 1, 'on': 1, 'off': 1, 'accept': 1,
    'accepted': 1, 'reject': 1, 'rejected': 1, 'allow': 1, 'deny': 1, 'necessary': 1, 'hide': 1, 'hidden': 1,
    '': 1, 'emptyArr': 1, 'emptyObj': 1, 'undefined': 1, 'null': 1, '$remove$': 1
  };
  if (allowed[raw] === 1 || /^-?\d{1,5}$/.test(raw)) {
    if (raw === 'emptyArr') return '[]';
    if (raw === 'emptyObj') return '{}';
    return raw;
  }
  return null;
}

/** Raw value of cookie [name] visible to the page, or undefined. */
function readCookie(name) {
  var parts = document.cookie.split(/\s*;\s*/);
  for (var i = 0; i < parts.length; i++) {
    var eq = parts[i].indexOf('=');
    var key = eq === -1 ? parts[i] : parts[i].slice(0, eq);
    if (key === name) return eq === -1 ? '' : parts[i].slice(eq + 1);
  }
  return undefined;
}

function writeCookie(name, value, path, maxAgeSeconds) {
  var cookie = name + '=' + value;
  cookie += '; path=' + (path === 'none' ? '' : (path || '/'));
  if (maxAgeSeconds !== undefined) cookie += '; max-age=' + maxAgeSeconds;
  document.cookie = cookie;
}

S['set-cookie'] = function (name, value, path) {
  if (!name) return;
  var safe = safeStorageValue(value === undefined ? '' : value);
  if (safe === null) return;
  writeCookie(encodeURIComponent(name), encodeURIComponent(safe), path);
};

/** Expands the `$now$` / `$currentDate$` / `$currentISODate$` placeholders of trusted values. */
function trustedValue(raw) {
  var now = new Date();
  return String(raw === undefined ? '' : raw)
    .replace('$now$', String(now.getTime()))
    .replace('$currentDate$', now.toUTCString())
    .replace('$currentISODate$', now.toISOString());
}

/** `3600`, `1day`, `1year` → seconds; anything else → session cookie. */
function cookieLifetime(raw) {
  if (raw === '1day') return 86400;
  if (raw === '1year') return 31536000;
  var seconds = parseInt(raw || '', 10);
  return isNaN(seconds) ? undefined : seconds;
}

function setTrustedCookie(name, value, offsetExpiresSec, path, reloadOnChange) {
  if (!name) return;
  var before = readCookie(name);
  writeCookie(name, trustedValue(value), path, cookieLifetime(offsetExpiresSec));
  // Reload only when the write changed something, so a cookie the page
  // rejects can never cause a reload loop.
  if (reloadOnChange && readCookie(name) !== before) location.reload();
}

S['trusted-set-cookie'] = function (name, value, offsetExpiresSec, path) {
  setTrustedCookie(name, value, offsetExpiresSec, path, false);
};

S['trusted-set-cookie-reload'] = function (name, value, offsetExpiresSec, path) {
  setTrustedCookie(name, value, offsetExpiresSec, path, true);
};

S['remove-cookie'] = function (needle) {
  var m = H.matcher(needle || '', true);
  var remove = function () {
    var cookies = document.cookie.split(';');
    for (var i = 0; i < cookies.length; i++) {
      var eq = cookies[i].indexOf('=');
      var name = (eq === -1 ? cookies[i] : cookies[i].slice(0, eq)).trim();
      if (!name || !m.test(name)) continue;
      var expire = name + '=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
      var host = location.hostname;
      document.cookie = expire;
      document.cookie = expire + '; path=/';
      document.cookie = expire + '; path=/; domain=' + host;
      document.cookie = expire + '; path=/; domain=.' + host.replace(/^www\./, '');
    }
  };
  remove();
  window.addEventListener('beforeunload', remove);
  document.addEventListener('visibilitychange', remove);
};

function setStorageItem(storageName, key, value, trusted) {
  if (!key) return;
  var storage;
  try { storage = window[storageName]; } catch (e) { return; }
  if (!storage) return;
  var v = trusted ? trustedValue(value) : safeStorageValue(value === undefined ? '' : value);
  if (v === null) return;
  try {
    if (v === '$remove$') {
      var m = H.matcher(key, false);
      var keys = [];
      for (var i = 0; i < storage.length; i++) keys.push(storage.key(i));
      for (var j = 0; j < keys.length; j++) if (m.test(keys[j])) storage.removeItem(keys[j]);
      return;
    }
    storage.setItem(key, v);
  } catch (e) { /* quota / access denied */ }
}

S['set-local-storage-item'] = function (key, value) { setStorageItem('localStorage', key, value, false); };
S['set-session-storage-item'] = function (key, value) { setStorageItem('sessionStorage', key, value, false); };
S['trusted-set-local-storage-item'] = function (key, value) { setStorageItem('localStorage', key, value, true); };
S['trusted-set-session-storage-item'] = function (key, value) { setStorageItem('sessionStorage', key, value, true); };

/**
 * href-sanitizer(selector, source): rewrites tracking links to their real
 * target, taken from the link text (`text`), a query parameter (`?param`)
 * or an attribute (`[attr]`).
 */
S['href-sanitizer'] = function (selector, source) {
  if (!selector) return;
  var src = source || 'text';
  var extract = function (a) {
    if (src === 'text') return (a.textContent || '').trim();
    if (src.charAt(0) === '?') {
      try { return new URL(a.href, location.href).searchParams.get(src.slice(1)); } catch (e) { return null; }
    }
    var attr = /^\[([^\]]+)\]$/.exec(src);
    return attr ? a.getAttribute(attr[1]) : null;
  };
  var run = function () {
    forEachMatch(selector, function (a) {
      if (a.nodeName !== 'A' || !a.hasAttribute('href')) return;
      var target = extract(a);
      if (!target) return;
      var url;
      try { url = new URL(target, location.href); } catch (e) { return; }
      if (url.protocol !== 'http:' && url.protocol !== 'https:') return;
      if (a.href !== url.href) a.setAttribute('href', url.href);
    });
  };
  H.runAt(run, 'interactive', true);
};

/**
 * trusted-click-element(selectors, extraMatch, delay): clicks each selector
 * of the comma-separated list in order once it appears (max 10 s).
 */
S['trusted-click-element'] = function (selectors, extraMatch, delay) {
  if (!selectors) return;
  var queue = selectors.split(/\s*,\s*/).filter(Boolean);
  var wait = parseInt(delay || '', 10);
  if (isNaN(wait) || wait < 0) wait = 0;
  if (extraMatch) {
    var cookieNeeded = /^cookie:(.+)$/.exec(extraMatch);
    if (cookieNeeded && document.cookie.indexOf(cookieNeeded[1]) === -1) return;
  }
  var deadline = Date.now() + 10000;
  var observer = null;
  var step = function () {
    while (queue.length) {
      var el;
      try { el = document.querySelector(queue[0]); } catch (e) { queue.shift(); continue; }
      if (!el) break;
      queue.shift();
      try { el.click(); } catch (e) { /* ignore */ }
    }
    if ((queue.length === 0 || Date.now() > deadline) && observer) observer.disconnect();
  };
  H.onDomReady(function () {
    H.natives.setTimeout(function () {
      step();
      if (queue.length === 0 || !document.documentElement) return;
      observer = new MutationObserver(step);
      observer.observe(document.documentElement, { childList: true, subtree: true });
      H.natives.setTimeout(function () { if (observer) observer.disconnect(); }, 10000);
    }, wait);
  });
};
