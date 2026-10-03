/*
 * fetch / XHR scriptlets.
 *
 * `fetchHooks` and `xhrHooks` patch fetch() and XMLHttpRequest once and
 * dispatch to every registered scriptlet, so a page with several response
 * filters pays for one wrapper, and a response is read and rebuilt once.
 * XHR responses are rewritten lazily in the `response` / `responseText` /
 * `responseXML` getters: whatever listener reads them, in whatever order,
 * sees the filtered body.
 */

/** Request details for `propsToMatch` from fetch() arguments. */
function fetchDetails(args) {
  var input = args[0];
  var init = args[1] || {};
  var details = { url: '', method: 'GET' };
  if (typeof Request === 'function' && input instanceof Request) {
    details.url = input.url;
    details.method = input.method;
    details.mode = input.mode;
    details.credentials = input.credentials;
    details.cache = input.cache;
    details.redirect = input.redirect;
    details.referrer = input.referrer;
  } else {
    details.url = String(input);
  }
  var keys = ['method', 'body', 'mode', 'credentials', 'cache', 'redirect', 'referrer', 'referrerPolicy', 'integrity'];
  for (var i = 0; i < keys.length; i++) if (init[keys[i]] !== undefined) details[keys[i]] = String(init[keys[i]]);
  return details;
}

/** A Response carrying [body] that otherwise looks like [model] (status, headers, url, type). */
function rebuiltResponse(model, body) {
  var out = new Response(body, { status: model.status, statusText: model.statusText, headers: model.headers });
  try {
    H.natives.defineProperty(out, 'url', { value: model.url });
    H.natives.defineProperty(out, 'type', { value: model.type });
    H.natives.defineProperty(out, 'redirected', { value: model.redirected });
  } catch (e) { /* ignore */ }
  return out;
}

var fetchHooks = (function () {
  var requestHandlers = [];
  var responseHandlers = [];
  var installed = false;

  function transform(response, handlers, details) {
    return response.clone().text().then(function (text) {
      var out = text;
      for (var i = 0; i < handlers.length; i++) {
        try { out = handlers[i].transform(out, details); } catch (e) { /* keep previous text */ }
      }
      return out === text ? response : rebuiltResponse(response, out);
    }).catch(function () { return response; });
  }

  function install() {
    if (installed || typeof window.fetch !== 'function') return;
    installed = true;
    H.wrapFunction(window, 'fetch', function (target, thisArg, args) {
      var details;
      try { details = fetchDetails(args); } catch (e) { return H.natives.Reflect.apply(target, thisArg, args); }
      for (var i = 0; i < requestHandlers.length; i++) {
        var replacement = requestHandlers[i](details, args);
        if (replacement) return H.natives.Promise.resolve(replacement);
      }
      var promise = H.natives.Reflect.apply(target, thisArg, args);
      var matching = [];
      for (var j = 0; j < responseHandlers.length; j++) {
        if (H.matchesProps(responseHandlers[j].props, details)) matching.push(responseHandlers[j]);
      }
      if (matching.length === 0) return promise;
      return promise.then(function (response) { return transform(response, matching, details); });
    });
  }

  return {
    /**
     * [handler](details, args) may edit `args` in place, or return a Response
     * that answers the request without touching the network.
     */
    onRequest: function (handler) { install(); requestHandlers.push(handler); },
    /** Rewrites the text of responses whose request matches [props]. */
    onResponse: function (props, transformText) { install(); responseHandlers.push({ props: props, transform: transformText }); }
  };
})();

var xhrHooks = (function () {
  var sendHandlers = [];
  var responseHandlers = [];
  var openInstalled = false;
  var gettersInstalled = false;
  var xhrData = new H.natives.WeakMap();
  var nativeGetters = {};
  var proto = typeof XMLHttpRequest === 'function' ? XMLHttpRequest.prototype : null;

  function installOpenSend() {
    if (openInstalled || !proto) return;
    openInstalled = true;
    H.wrapFunction(proto, 'open', function (target, thisArg, args) {
      xhrData.set(thisArg, { method: String(args[0] || 'GET').toUpperCase(), url: String(args[1] || '') });
      return H.natives.Reflect.apply(target, thisArg, args);
    });
    H.wrapFunction(proto, 'send', function (target, thisArg, args) {
      var details = xhrData.get(thisArg);
      if (details) {
        details.body = args[0] === undefined || args[0] === null ? '' : String(args[0]);
        for (var i = 0; i < sendHandlers.length; i++) {
          if (sendHandlers[i](thisArg, details, args) === true) return undefined;
        }
        var matching = [];
        for (var j = 0; j < responseHandlers.length; j++) {
          if (H.matchesProps(responseHandlers[j].props, details)) matching.push(responseHandlers[j]);
        }
        details.handlers = matching;
        details.cache = null;
      }
      return H.natives.Reflect.apply(target, thisArg, args);
    });
  }

  /** Filtered text of a finished request, computed once per raw body. */
  function filteredText(xhr, details, rawText) {
    var cache = details.cache;
    if (cache && cache.raw === rawText) return cache.text;
    var out = rawText;
    for (var i = 0; i < details.handlers.length; i++) {
      try { out = details.handlers[i].transform(out, details); } catch (e) { /* keep previous text */ }
    }
    details.cache = { raw: rawText, text: out, json: undefined, doc: undefined };
    return out;
  }

  function wrapGetter(name, compute) {
    var desc = H.natives.getOwnPropertyDescriptor(proto, name);
    if (!desc || typeof desc.get !== 'function') return;
    var nativeGet = desc.get;
    nativeGetters[name] = nativeGet;
    H.define(proto, name, {
      configurable: true,
      enumerable: desc.enumerable,
      get: H.cloak(function () {
        var raw = nativeGet.call(this);
        var details = xhrData.get(this);
        if (!details || !details.handlers || details.handlers.length === 0 || this.readyState !== 4) return raw;
        try { return compute(this, details, raw); } catch (e) { return raw; }
      }, nativeGet)
    });
  }

  function textOf(xhr, raw) {
    var type = xhr.responseType;
    if (type === '' || type === 'text') return typeof raw === 'string' ? raw : null;
    if (type === 'json') return raw === null ? null : H.natives.jsonStringify(raw);
    return null;
  }

  function installGetters() {
    if (gettersInstalled || !proto) return;
    gettersInstalled = true;
    wrapGetter('response', function (xhr, details, raw) {
      var type = xhr.responseType;
      if (type === 'document') {
        var doc = xhr.responseXML;
        return doc === null ? raw : doc;
      }
      var text = textOf(xhr, raw);
      if (text === null) return raw;
      var out = filteredText(xhr, details, text);
      if (out === text) return raw;
      if (type !== 'json') return out;
      if (details.cache.json === undefined) {
        try { details.cache.json = H.natives.jsonParse(out); } catch (e) { details.cache.json = null; }
      }
      return details.cache.json;
    });
    wrapGetter('responseText', function (xhr, details, raw) {
      return typeof raw === 'string' ? filteredText(xhr, details, raw) : raw;
    });
    wrapGetter('responseXML', function (xhr, details, raw) {
      if (raw === null || typeof XMLSerializer !== 'function' || typeof DOMParser !== 'function') return raw;
      var text = xhr.responseType === 'document'
        ? new XMLSerializer().serializeToString(raw)
        : nativeGetters.responseText.call(xhr);
      var out = filteredText(xhr, details, text);
      if (out === text) return raw;
      if (details.cache.doc === undefined) {
        var mime = raw.contentType && raw.contentType.indexOf('html') !== -1 ? 'text/html' : 'text/xml';
        details.cache.doc = new DOMParser().parseFromString(out, mime);
      }
      return details.cache.doc;
    });
  }

  return {
    /** [handler](xhr, details, args) returns true when it answered the request itself. */
    onSend: function (handler) { installOpenSend(); sendHandlers.push(handler); },
    /** Rewrites the text of responses whose request matches [props]. */
    onResponse: function (props, transformText) {
      installOpenSend();
      installGetters();
      responseHandlers.push({ props: props, transform: transformText });
    }
  };
})();

/** Registers [transformText] for both fetch() and XHR responses matching [props]. */
function onResponseText(props, transformText) {
  fetchHooks.onResponse(props, transformText);
  xhrHooks.onResponse(props, transformText);
}

// ── Request blocking ───────────────────────────────────────────────────

/** Response bodies untrusted filters may fake. */
function safeResponseBody(directive) {
  switch (directive) {
    case 'emptyObj': return '{}';
    case 'emptyArr': return '[]';
    case 'true': return Math.random().toString(36).slice(2);
    default: return '';
  }
}

/** `{"status":…,"statusText":…,"type":…}` for trusted-prevent-fetch, validated. */
function responseOverrides(raw) {
  var out = {};
  if (!raw) return out;
  var parsed;
  try { parsed = H.natives.jsonParse(raw); } catch (e) { return out; }
  if (parsed === null || typeof parsed !== 'object') return out;
  if (typeof parsed.status === 'number' && parsed.status >= 200 && parsed.status <= 599) out.status = parsed.status | 0;
  if (typeof parsed.statusText === 'string') out.statusText = parsed.statusText;
  if (/^(basic|cors|default|opaque)$/.test(parsed.type || '')) out.type = parsed.type;
  return out;
}

function preventFetch(propsToMatch, body, responseType, overrides) {
  var props = H.parsePropsToMatch(propsToMatch);
  if (props.length === 0) return;
  fetchHooks.onRequest(function (details) {
    if (!H.matchesProps(props, details)) return undefined;
    var response = new Response(body, { status: overrides.status || 200, statusText: overrides.statusText || 'OK' });
    var type = overrides.type || responseType || (details.mode === 'no-cors' ? 'opaque' : 'basic');
    try {
      H.natives.defineProperty(response, 'url', { value: details.url });
      H.natives.defineProperty(response, 'type', { value: type });
    } catch (e) { /* ignore */ }
    return response;
  });
}

S['no-fetch-if'] = function (propsToMatch, responseBody, responseType) {
  var type = /^(basic|cors|opaque)$/.test(responseType || '') ? responseType : undefined;
  preventFetch(propsToMatch, safeResponseBody(responseBody), type, {});
};

S['trusted-prevent-fetch'] = function (propsToMatch, responseBody, responseProps) {
  preventFetch(propsToMatch, responseBody === undefined ? '' : String(responseBody), undefined, responseOverrides(responseProps));
};

function defineXhrResponse(xhr, text) {
  var json;
  var responseValue = function () {
    if (xhr.responseType === 'json') {
      if (json === undefined) { try { json = H.natives.jsonParse(text); } catch (e) { json = null; } }
      return json;
    }
    return text;
  };
  try {
    H.define(xhr, 'responseText', { configurable: true, get: function () { return text; } });
    H.define(xhr, 'response', { configurable: true, get: responseValue });
  } catch (e) { /* ignore */ }
}

function preventXhr(propsToMatch, body) {
  var props = H.parsePropsToMatch(propsToMatch);
  if (props.length === 0) return;
  xhrHooks.onSend(function (xhr, details) {
    if (!H.matchesProps(props, details)) return false;
    var define = function (name, value) {
      try { H.define(xhr, name, { configurable: true, get: function () { return value; } }); } catch (e) { /* ignore */ }
    };
    define('readyState', 4);
    define('status', 200);
    define('statusText', 'OK');
    define('responseURL', details.url);
    defineXhrResponse(xhr, body);
    H.natives.setTimeout(function () {
      var events = ['readystatechange', 'load', 'loadend'];
      for (var i = 0; i < events.length; i++) {
        try { xhr.dispatchEvent(new Event(events[i])); } catch (e) { /* ignore */ }
      }
    }, 1);
    return true;
  });
}

S['no-xhr-if'] = function (propsToMatch, directive) { preventXhr(propsToMatch, safeResponseBody(directive)); };

S['trusted-prevent-xhr'] = function (propsToMatch, directive) {
  preventXhr(propsToMatch, directive === undefined ? '' : String(directive));
};

// ── json-prune ─────────────────────────────────────────────────────────

/**
 * Visits (and with [prune] deletes) the property at a json-prune [path]:
 * `a.b`, `*` / `[]` (any child), `[-]` (remove the array elements that
 * contain the rest of the path).
 */
function walkJsonPath(owner, path, prune) {
  if (owner === null || typeof owner !== 'object') return false;
  var pos = path.indexOf('.');
  var head = pos === -1 ? path : path.slice(0, pos);
  var rest = pos === -1 ? '' : path.slice(pos + 1);
  if (head === '[-]') {
    if (!rest || !Array.isArray(owner)) return false;
    var hit = false;
    for (var k = owner.length - 1; k >= 0; k--) {
      if (!walkJsonPath(owner[k], rest, false)) continue;
      hit = true;
      if (!prune) return true;
      owner.splice(k, 1);
    }
    return hit;
  }
  if (head === '*' || head === '[]') {
    if (!rest || (head === '[]' && !Array.isArray(owner))) return false;
    var found = false;
    var keys = Object.keys(owner);
    for (var i = 0; i < keys.length; i++) {
      if (walkJsonPath(owner[keys[i]], rest, prune)) {
        found = true;
        if (!prune) return true;
      }
    }
    return found;
  }
  if (!rest) {
    if (!Object.prototype.hasOwnProperty.call(owner, head)) return false;
    if (prune) delete owner[head];
    return true;
  }
  return walkJsonPath(owner[head], rest, prune);
}

/**
 * Returns a json-prune function that prunes an object in place and reports
 * whether anything was removed, or null when the filter prunes nothing.
 */
function jsonPruner(rawPrunePaths, rawNeedlePaths) {
  var prunePaths = (rawPrunePaths || '').split(/\s+/).filter(Boolean);
  if (prunePaths.length === 0) return null;
  var needlePaths = (rawNeedlePaths || '').split(/\s+/).filter(Boolean);
  return function (obj) {
    if (obj === null || typeof obj !== 'object') return false;
    for (var i = 0; i < needlePaths.length; i++) {
      if (!walkJsonPath(obj, needlePaths[i], false)) return false;
    }
    var pruned = false;
    for (var j = 0; j < prunePaths.length; j++) {
      if (walkJsonPath(obj, prunePaths[j], true)) pruned = true;
    }
    return pruned;
  };
}

/**
 * JSON text transform: parses [text], lets [edit] modify the object (it
 * returns the resulting object, or undefined when it changed nothing) and
 * serialises the result. Unchanged or non-JSON bodies are returned as is.
 */
function jsonTextTransform(edit) {
  return function (text) {
    var obj;
    try { obj = H.natives.jsonParse(text); } catch (e) { return text; }
    if (obj === null || typeof obj !== 'object') return text;
    var after = edit(obj);
    return after === undefined ? text : H.natives.jsonStringify(after);
  };
}

/** [jsonTextTransform] for a [jsonPruner]. */
function pruneTextTransform(prune) {
  return jsonTextTransform(function (obj) { return prune(obj) ? obj : undefined; });
}

S['json-prune'] = function (rawPrunePaths, rawNeedlePaths) {
  var prune = jsonPruner(rawPrunePaths, rawNeedlePaths);
  if (!prune) return;
  H.proxyApply('JSON.parse', function (ctx) {
    var obj = ctx.reflect();
    prune(obj);
    return obj;
  });
  if (typeof Response === 'function') {
    H.wrapFunction(Response.prototype, 'json', function (target, thisArg, args) {
      return H.natives.Reflect.apply(target, thisArg, args).then(function (obj) {
        prune(obj);
        return obj;
      });
    });
  }
};

S['json-prune-fetch-response'] = function (rawPrunePaths, rawNeedlePaths) {
  var prune = jsonPruner(rawPrunePaths, rawNeedlePaths);
  if (!prune) return;
  var props = H.parsePropsToMatch(H.extraArgs(arguments, 2).propsToMatch || '');
  fetchHooks.onResponse(props, pruneTextTransform(prune));
};

S['json-prune-xhr-response'] = function (rawPrunePaths, rawNeedlePaths) {
  var prune = jsonPruner(rawPrunePaths, rawNeedlePaths);
  if (!prune) return;
  var props = H.parsePropsToMatch(H.extraArgs(arguments, 2).propsToMatch || '');
  xhrHooks.onResponse(props, pruneTextTransform(prune));
};

// ── Text replacement ───────────────────────────────────────────────────

function replacer(pattern, replacement) {
  if (!pattern) return null;
  var re = H.toRegExp(pattern, 'g');
  if (!re) return null;
  var with_ = replacement || '';
  return function (text) { return text.replace(re, with_); };
}

S['trusted-replace-fetch-response'] = function (pattern, replacement, propsToMatch) {
  var replace = replacer(pattern, replacement);
  if (replace) fetchHooks.onResponse(H.parsePropsToMatch(propsToMatch || ''), replace);
};

S['trusted-replace-xhr-response'] = function (pattern, replacement, propsToMatch) {
  var replace = replacer(pattern, replacement);
  if (replace) xhrHooks.onResponse(H.parsePropsToMatch(propsToMatch || ''), replace);
};
