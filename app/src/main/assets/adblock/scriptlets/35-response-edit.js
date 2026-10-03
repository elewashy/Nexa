/* Response pruning (XML / HLS / DASH) and the json-edit family. */

/** uBO `patternToRegex` for URL arguments: '' → everything, `/re/` → regex, else literal. */
function urlMatcherProps(urlPattern) {
  return H.parsePropsToMatch(urlPattern ? 'url:' + urlPattern : '');
}

// ── XML ────────────────────────────────────────────────────────────────

/** Nodes of [doc] matching a CSS selector or an `xpath(…)` expression. */
function queryXml(doc, selector) {
  if (/^xpath\(.+\)$/.test(selector)) {
    var out = [];
    var result = doc.evaluate(selector.slice(6, -1), doc, null, XPathResult.UNORDERED_NODE_SNAPSHOT_TYPE, null);
    for (var i = 0; i < result.snapshotLength; i++) out.push(result.snapshotItem(i));
    return out;
  }
  return Array.prototype.slice.call(doc.querySelectorAll(selector));
}

/**
 * Text transform removing the elements (or attributes) matched by [selector]
 * from XML documents, when [selectorCheck] (if any) matches. [rootTest]
 * limits it to a document kind (e.g. DASH manifests).
 */
function xmlPruner(selector, selectorCheck, rootTest) {
  return function (text) {
    if (!/^\s*</.test(text) || !/>\s*$/.test(text)) return text;
    if (rootTest && !rootTest.test(text)) return text;
    var doc = new DOMParser().parseFromString(text, 'text/xml');
    if (doc.getElementsByTagName('parsererror').length !== 0) return text;
    if (selectorCheck && doc.querySelector(selectorCheck) === null) return text;
    var nodes = queryXml(doc, selector);
    if (nodes.length === 0) return text;
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      if (node.nodeType === 1) node.remove();
      else if (node.nodeType === 2 && node.ownerElement) node.ownerElement.removeAttribute(node.nodeName);
    }
    return new XMLSerializer().serializeToString(doc);
  };
}

S['xml-prune'] = function (selector, selectorCheck, urlPattern) {
  if (!selector) return;
  onResponseText(urlMatcherProps(urlPattern), xmlPruner(selector, selectorCheck || '', null));
};

/** mpegdash-prune(selector, urlPattern): xml-prune restricted to DASH manifests (`<MPD>`). */
S['mpegdash-prune'] = function (selector, urlPattern) {
  if (!selector) return;
  onResponseText(urlMatcherProps(urlPattern), xmlPruner(selector, '', /<MPD[\s>]/));
};

// ── HLS ────────────────────────────────────────────────────────────────

function m3uRegex(arg) {
  if (!arg) return /^/;
  var m = /^\/([\s\S]+)\/([gims]*)$/.exec(arg);
  if (m) {
    var flags = m[2];
    if (flags.indexOf('m') !== -1 && flags.indexOf('g') === -1) flags += 'g';
    try { return new H.natives.RegExp(m[1], flags); } catch (e) { return null; }
  }
  return new H.natives.RegExp(arg.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*+/g, '.*?'));
}

/**
 * uBO m3u-prune: drops the `#EXTINF` segments whose URI matches [pattern]
 * (with a following discontinuity tag), SCTE-35 SpliceOut ad blocks, and —
 * for multiline (`m`) patterns — every matched span.
 */
function m3uPruner(pattern) {
  var re = m3uRegex(pattern);
  if (!re) return null;
  var startsWith = function (lines, i, prefix) { return lines[i] !== undefined && lines[i].indexOf(prefix) === 0; };
  var testLine = function (line) {
    re.lastIndex = 0;
    return re.test(line);
  };
  return function (text) {
    if (!/^\s*#EXTM3U/.test(text)) return text;
    if (re.multiline) {
      re.lastIndex = 0;
      for (;;) {
        var match = re.exec(text);
        if (match === null || match[0] === '') break;
        var before = text.slice(0, match.index);
        if (!/^[\n\r]+/.test(match[0]) && !/[\n\r]+$/.test(before)) {
          var lineStart = /[^\n\r]+$/.exec(before);
          if (lineStart !== null) before = before.slice(0, lineStart.index);
        }
        var after = text.slice(match.index + match[0].length);
        if (!/[\n\r]+$/.test(match[0]) && !/^[\n\r]+/.test(after)) {
          var lineEnd = /^[^\n\r]+/.exec(after);
          if (lineEnd !== null) after = after.slice(lineEnd[0].length);
        }
        text = before.trim() + '\n' + after.trim();
        re.lastIndex = before.length + 1;
        if (!re.global) break;
      }
    }
    var lines = text.split(/\n\r|\n|\r/);
    for (var i = 0; i < lines.length; i++) {
      if (lines[i] === undefined) continue;
      if (startsWith(lines, i, '#EXT-X-CUE:TYPE="SpliceOut"')) {
        lines[i++] = undefined;
        if (startsWith(lines, i, '#EXT-X-ASSET:CAID')) lines[i++] = undefined;
        if (startsWith(lines, i, '#EXT-X-SCTE35:')) lines[i++] = undefined;
        if (startsWith(lines, i, '#EXT-X-CUE-IN')) lines[i++] = undefined;
        if (startsWith(lines, i, '#EXT-X-SCTE35:')) lines[i++] = undefined;
        i--;
        continue;
      }
      if (startsWith(lines, i, '#EXTINF') && lines[i + 1] !== undefined && testLine(lines[i + 1])) {
        lines[i] = lines[i + 1] = undefined;
        if (startsWith(lines, i + 2, '#EXT-X-DISCONTINUITY')) lines[i + 2] = undefined;
      }
    }
    return lines.filter(function (l) { return l !== undefined; }).join('\n');
  };
}

S['m3u-prune'] = function (m3uPattern, urlPattern) {
  if (!m3uPattern) return;
  var prune = m3uPruner(m3uPattern);
  if (!prune) return;
  var url = m3uRegex(urlPattern || '');
  if (!url) return;
  var props = [{ key: 'url', m: { test: function (s) { url.lastIndex = 0; return url.test(String(s)); } } }];
  onResponseText(props, prune);
};

// ── json-edit family ───────────────────────────────────────────────────

/** Compiled JSONPath for a json-edit filter; assignments need a trusted filter. */
function jsonEditQuery(trusted, query) {
  var jp = JsonPath.create(query || '');
  if (!jp.valid || (jp.hasValue && !trusted)) return null;
  return jp;
}

function jsonEdit(trusted, query) {
  var jp = jsonEditQuery(trusted, query);
  if (!jp) return;
  H.proxyApply('JSON.parse', function (ctx) {
    var obj = ctx.reflect();
    if (obj === null || typeof obj !== 'object') return obj;
    var edited = jp.apply(obj);
    return edited === undefined ? obj : edited;
  });
}

function jsonEditResponse(trusted, hooks, query, args) {
  var jp = jsonEditQuery(trusted, query);
  if (!jp) return;
  var props = H.parsePropsToMatch(H.extraArgs(args, 1).propsToMatch || '');
  hooks.onResponse(props, jsonTextTransform(function (obj) { return jp.apply(obj); }));
}

/** JSON-lines responses: each line holding a JSON object is edited separately. */
function jsonlEditResponse(trusted, hooks, query, args) {
  var jp = jsonEditQuery(trusted, query);
  if (!jp) return;
  var props = H.parsePropsToMatch(H.extraArgs(args, 1).propsToMatch || '');
  var editLine = jsonTextTransform(function (obj) { return jp.apply(obj); });
  hooks.onResponse(props, function (text) {
    var separator = /\r\n/.test(text) ? '\r\n' : '\n';
    var lines = text.split(separator);
    var changed = false;
    for (var i = 0; i < lines.length; i++) {
      var edited = editLine(lines[i]);
      if (edited !== lines[i]) { lines[i] = edited; changed = true; }
    }
    return changed ? lines.join(separator) : text;
  });
}

/** Edits a JSON request body (string) in place; returns the new body or the input. */
function editJsonBody(jp, body) {
  if (typeof body !== 'string') return body;
  return jsonTextTransform(function (obj) { return jp.apply(obj); })(body);
}

function jsonEditFetchRequest(trusted, query, args) {
  var jp = jsonEditQuery(trusted, query);
  if (!jp) return;
  var props = H.parsePropsToMatch(H.extraArgs(args, 1).propsToMatch || '');
  fetchHooks.onRequest(function (details, fetchArgs) {
    var init = fetchArgs[1];
    if (!init || typeof init.body !== 'string' || !H.matchesProps(props, details)) return undefined;
    var body = editJsonBody(jp, init.body);
    if (body !== init.body) {
      var copy = {};
      for (var k in init) copy[k] = init[k];
      copy.body = body;
      fetchArgs[1] = copy;
    }
    return undefined;
  });
}

function jsonEditXhrRequest(trusted, query, args) {
  var jp = jsonEditQuery(trusted, query);
  if (!jp) return;
  var props = H.parsePropsToMatch(H.extraArgs(args, 1).propsToMatch || '');
  xhrHooks.onSend(function (xhr, details, sendArgs) {
    if (typeof sendArgs[0] !== 'string' || !H.matchesProps(props, details)) return false;
    sendArgs[0] = editJsonBody(jp, sendArgs[0]);
    details.body = sendArgs[0];
    return false;
  });
}

S['json-edit'] = function (query) { jsonEdit(false, query); };
S['trusted-json-edit'] = function (query) { jsonEdit(true, query); };
S['json-edit-fetch-response'] = function (query) { jsonEditResponse(false, fetchHooks, query, arguments); };
S['trusted-json-edit-fetch-response'] = function (query) { jsonEditResponse(true, fetchHooks, query, arguments); };
S['json-edit-xhr-response'] = function (query) { jsonEditResponse(false, xhrHooks, query, arguments); };
S['trusted-json-edit-xhr-response'] = function (query) { jsonEditResponse(true, xhrHooks, query, arguments); };
S['json-edit-fetch-request'] = function (query) { jsonEditFetchRequest(false, query, arguments); };
S['trusted-json-edit-fetch-request'] = function (query) { jsonEditFetchRequest(true, query, arguments); };
S['json-edit-xhr-request'] = function (query) { jsonEditXhrRequest(false, query, arguments); };
S['trusted-json-edit-xhr-request'] = function (query) { jsonEditXhrRequest(true, query, arguments); };
S['jsonl-edit-fetch-response'] = function (query) { jsonlEditResponse(false, fetchHooks, query, arguments); };
S['trusted-jsonl-edit-fetch-response'] = function (query) { jsonlEditResponse(true, fetchHooks, query, arguments); };
S['jsonl-edit-xhr-response'] = function (query) { jsonlEditResponse(false, xhrHooks, query, arguments); };
S['trusted-jsonl-edit-xhr-response'] = function (query) { jsonlEditResponse(true, xhrHooks, query, arguments); };

// ── Object editing ─────────────────────────────────────────────────────

function cloneJson(obj) {
  try { return H.natives.jsonParse(H.natives.jsonStringify(obj)); } catch (e) { return undefined; }
}

/**
 * edit-inbound-object(fn, argpos, query): edits a copy of argument [argpos]
 * (negative = from the end) of every call to [fn] before the call proceeds.
 */
function editInboundObject(trusted, chain, rawArgPos, query) {
  var argPos = parseInt(rawArgPos, 10);
  if (!chain || isNaN(argPos)) return;
  var jp = jsonEditQuery(trusted, query);
  if (!jp) return;
  H.proxyApply(chain, function (ctx) {
    var args = ctx.callArgs;
    var i = argPos >= 0 ? argPos : args.length + argPos;
    // Match first: hot functions such as JSON.stringify must not pay for a
    // deep copy of every argument the query does not select.
    if (i >= 0 && i < args.length && args[i] !== null && typeof args[i] === 'object' && jp.test(args[i])) {
      var copy = cloneJson(args[i]);
      if (copy !== undefined) {
        var edited = jp.apply(copy);
        if (edited !== undefined) args[i] = edited;
      }
    }
    return ctx.reflect();
  });
}

/** edit-outbound-object(fn, query): edits the object returned by [fn]. */
function editOutboundObject(trusted, chain, query) {
  if (!chain) return;
  var jp = jsonEditQuery(trusted, query);
  if (!jp) return;
  H.proxyApply(chain, function (ctx) {
    var result = ctx.reflect();
    if (result === null || typeof result !== 'object') return result;
    var edited = jp.apply(result);
    return edited === undefined ? result : edited;
  });
}

/** edit-object-on-setter(chain, query): edits objects as they are assigned to [chain]. */
function editObjectOnSetter(trusted, chain, query) {
  if (!chain) return;
  var jp = jsonEditQuery(trusted, query);
  if (!jp) return;
  var edit = function (v) {
    if (v === null || typeof v !== 'object') return v;
    var edited = jp.apply(v);
    return edited === undefined ? v : edited;
  };
  H.trapChain(window, chain, function (owner, prop) {
    var desc = H.natives.getOwnPropertyDescriptor(owner, prop);
    if (desc && desc.configurable === false) return;
    var value = edit(desc && 'value' in desc ? desc.value : undefined);
    var setter = desc && desc.set;
    var getter = desc && desc.get;
    try {
      H.define(owner, prop, {
        configurable: true,
        enumerable: desc ? desc.enumerable : true,
        get: function () { return getter ? getter.call(this) : value; },
        set: function (v) {
          var edited = edit(v);
          if (setter) setter.call(this, edited); else value = edited;
        }
      });
    } catch (e) { /* frozen owner */ }
  });
}

S['edit-inbound-object'] = function (chain, argPos, query) { editInboundObject(false, chain, argPos, query); };
S['trusted-edit-inbound-object'] = function (chain, argPos, query) { editInboundObject(true, chain, argPos, query); };
S['edit-outbound-object'] = function (chain, query) { editOutboundObject(false, chain, query); };
S['trusted-edit-outbound-object'] = function (chain, query) { editOutboundObject(true, chain, query); };
S['edit-object-on-setter'] = function (chain, query) { editObjectOnSetter(false, chain, query); };
S['trusted-edit-object-on-setter'] = function (chain, query) { editObjectOnSetter(true, chain, query); };
