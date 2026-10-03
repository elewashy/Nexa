/*
 * JSONPath subset used by uBO's `json-edit` / `edit-*-object` scriptlets.
 *
 *   $          root                    .key  ..key   children / descendants
 *   *  [*]     every child             ['k'] [0] [-1] [a,b]  bracket keys
 *   [?path]    keep nodes (array: elements) for which `path` exists
 *   [?path==v] comparison (== != < <= > >= ^= $= *=; v is JSON) or =/regex/
 *   [?!path]   negation
 *   v2:        prefix enabling `/regex/` keys (`v2:$./^adv_/`)
 *
 * A query without assignment removes what it selects. Trusted filters may
 * append `=json` (assign), `+={…}` (merge into an object) or
 * `=repl({"regex"|"pattern":…,"replacement":…})` (rewrite a string);
 * `${now}` in a string value expands to the current time.
 */
var JsonPath = (function () {
  var natives = H.natives;
  var ROOT = 1;
  var CURRENT = 2;
  var CHILDREN = 3;
  var DESCENDANTS = 4;
  var MAX_DEPTH = 64;
  var reUnquoted = /^(?:[A-Za-z_$][\w$]*|\*|\d+)/;
  var reRegexKey = /^\/((?:[^/\\\n]|\\.)+)\/([imsu]*)/;
  var reOperator = /^(?:[!=^$*]=|[<>]=?|=)/;
  var reIndex = /^-?\d+/;

  var INVALID = {
    valid: false,
    hasValue: false,
    test: function () { return false; },
    apply: function () { return undefined; }
  };

  function compile(query, i, v2, nested) {
    if (i >= query.length) return undefined;
    var steps = [];
    var c = query.charAt(i);
    steps.push({ mv: c === '$' ? ROOT : CURRENT });
    if (c === '$') i += 1;
    var mv = 0;
    for (;;) {
      if (i >= query.length) break;
      c = query.charAt(i);
      if (c === ' ') { i += 1; continue; }
      if (c === '.') {
        if (mv !== 0) return undefined;
        if (query.charAt(i + 1) === '.') { mv = DESCENDANTS; i += 2; } else { mv = CHILDREN; i += 1; }
        continue;
      }
      if (c !== '[') {
        if (mv === 0) {
          // End of the path: a filter comparison may follow (nested only).
          if (nested) i = compileExpr(query, steps[steps.length - 1], i);
          break;
        }
        var id = consumeUnquoted(query, i, v2);
        if (id === undefined) return undefined;
        steps.push({ mv: mv, k: id.k });
        i += id.len;
        mv = 0;
        continue;
      }
      if (query.charAt(i + 1) === '?') {
        var not = query.charAt(i + 2) === '!';
        var sub = compile(query, i + 2 + (not ? 1 : 0), v2, true);
        if (sub === undefined || sub.steps.length <= 1 || query.charAt(sub.i) !== ']') return undefined;
        if (not) sub.steps[sub.steps.length - 1].not = true;
        steps.push({ mv: mv || CHILDREN, steps: sub.steps });
        i = sub.i + 1;
        mv = 0;
        continue;
      }
      if (query.slice(i, i + 3) === '[*]') {
        steps.push({ mv: mv || CHILDREN, k: '*' });
        i += 3;
        mv = 0;
        continue;
      }
      var keys = consumeBracket(query, i + 1);
      if (keys === undefined) return undefined;
      steps.push({ mv: mv || CHILDREN, k: keys.k });
      i = keys.i + 1;
      mv = 0;
    }
    return { steps: steps, i: i };
  }

  function consumeUnquoted(query, i, v2) {
    var rest = query.slice(i);
    if (v2) {
      var rm = reRegexKey.exec(rest);
      if (rm) {
        try { return { k: { re: new natives.RegExp(rm[1], rm[2]) }, len: rm[0].length }; } catch (e) { return undefined; }
      }
    }
    var m = reUnquoted.exec(rest);
    return m ? { k: m[0], len: m[0].length } : undefined;
  }

  function consumeBracket(query, i) {
    var keys = [];
    for (;;) {
      if (i >= query.length) return undefined;
      var c = query.charAt(i);
      if (c === ']') break;
      if (c === ',' || c === ' ') { i += 1; continue; }
      if (c === '\'' || c === '"') {
        var end = i + 1;
        var s = '';
        while (end < query.length && query.charAt(end) !== c) {
          if (query.charAt(end) === '\\' && end + 1 < query.length) end += 1;
          s += query.charAt(end);
          end += 1;
        }
        if (end >= query.length) return undefined;
        keys.push(s);
        i = end + 1;
        continue;
      }
      var im = reIndex.exec(query.slice(i));
      if (im) {
        keys.push(parseInt(im[0], 10));
        i += im[0].length;
        continue;
      }
      var id = consumeUnquoted(query, i, false);
      if (id === undefined) return undefined;
      keys.push(id.k);
      i += id.len;
    }
    return { k: keys.length === 1 ? keys[0] : keys, i: i };
  }

  /** Parses `op value` up to (not including) the closing `]` of a filter. */
  function compileExpr(query, step, i) {
    var om = reOperator.exec(query.slice(i));
    if (!om) return i;
    var op = om[0];
    var start = i + op.length;
    var end;
    if (query.charAt(start) === '/') {
      var rm = reRegexKey.exec(query.slice(start));
      if (!rm || query.charAt(start + rm[0].length) !== ']') return i;
      try { step.re = new natives.RegExp(rm[1], rm[2]); } catch (e) { return i; }
      step.op = op === '=' || op === '==' ? '=~' : op === '!=' ? '!~' : undefined;
      if (step.op === undefined) return i;
      return start + rm[0].length;
    }
    if (op === '=') return i;
    if (query.charAt(start) === '"') {
      end = start + 1;
      while (end < query.length && query.charAt(end) !== '"') end += query.charAt(end) === '\\' ? 2 : 1;
      end += 1;
    } else {
      end = query.indexOf(']', start);
      if (end === -1) return i;
    }
    if (query.charAt(end) !== ']') return i;
    try {
      step.rval = natives.jsonParse(query.slice(start, end));
      step.op = op;
    } catch (e) { return i; }
    return end;
  }

  // ── Evaluation ─────────────────────────────────────────────────────

  function Evaluator(root) {
    this.root = { $: root };
  }

  Evaluator.prototype.resolve = function (path) {
    var obj = this.root;
    for (var i = 0; i < path.length - 1; i++) obj = obj[path[i]];
    var key = path[path.length - 1];
    return { obj: obj, key: key, value: obj[key] };
  };

  Evaluator.prototype.evaluate = function (steps, pathIn) {
    var results = [];
    for (var s = 0; s < steps.length; s++) {
      var step = steps[s];
      if (step.mv === ROOT) results = [['$']];
      else if (step.mv === CURRENT) results = [pathIn];
      else results = this.matches(results, step);
      if (results.length === 0) break;
    }
    return results;
  };

  Evaluator.prototype.matches = function (listIn, step) {
    var out = [];
    for (var i = 0; i < listIn.length; i++) {
      var pathIn = listIn[i];
      var owner = this.resolve(pathIn).value;
      if (step.k === '*') this.fromAll(pathIn, step, owner, out);
      else if (step.k !== undefined) this.fromKeys(pathIn, step, owner, out);
      else if (step.steps) this.fromFilter(pathIn, step, owner, out);
    }
    return out;
  };

  Evaluator.prototype.fromAll = function (pathIn, step, owner, out) {
    descendants(owner, step.mv === DESCENDANTS, function (obj, key, path) { out.push(pathIn.concat(path)); });
  };

  Evaluator.prototype.fromKeys = function (pathIn, step, owner, out) {
    var keys = Array.isArray(step.k) ? step.k : [step.k];
    var visit = function (target, prefix) {
      for (var i = 0; i < keys.length; i++) {
        var k = keys[i];
        if (k !== null && typeof k === 'object' && k.re) {
          if (!isContainer(target)) continue;
          var own = natives.keys(target);
          for (var j = 0; j < own.length; j++) {
            if (!k.re.test(own[j])) continue;
            var n1 = testKey(step, target, Array.isArray(target) ? Number(own[j]) : own[j]);
            if (n1 !== undefined) out.push(prefix.concat([n1]));
          }
          continue;
        }
        var n = testKey(step, target, k);
        if (n !== undefined) out.push(prefix.concat([n]));
      }
    };
    visit(owner, pathIn);
    if (step.mv !== DESCENDANTS) return;
    descendants(owner, true, function (obj, key, path) { visit(obj[key], pathIn.concat(path)); });
  };

  Evaluator.prototype.fromFilter = function (pathIn, step, owner, out) {
    var self = this;
    var recursive = step.mv === DESCENDANTS;
    if (!Array.isArray(owner)) {
      if (self.evaluate(step.steps, pathIn).length !== 0) out.push(pathIn);
      if (!recursive) return;
    }
    descendants(owner, recursive, function (obj, key, path) {
      if (Array.isArray(obj[key])) return;
      var q = pathIn.concat(path);
      if (self.evaluate(step.steps, q).length !== 0) out.push(q);
    });
  };

  function isContainer(v) {
    return v !== null && (typeof v === 'object' || typeof v === 'function');
  }

  /** Pre-order walk of [value]'s children (all levels when [recursive]). */
  function descendants(value, recursive, visit) {
    var seen = [];
    var walk = function (obj, path, depth) {
      if (!isContainer(obj) || depth > MAX_DEPTH || seen.indexOf(obj) !== -1) return;
      seen.push(obj);
      var keys = Array.isArray(obj) ? obj.map(function (_, i) { return i; }) : natives.keys(obj);
      for (var i = 0; i < keys.length; i++) {
        var p = path.concat([keys[i]]);
        visit(obj, keys[i], p);
        if (recursive) walk(obj[keys[i]], p, depth + 1);
      }
      seen.pop();
    };
    walk(value, [], 0);
  }

  /** The normalised key when [owner] has [key] and the step's condition holds, else undefined. */
  function testKey(step, owner, key) {
    if (!isContainer(owner)) return undefined;
    var k = key;
    if (typeof key === 'number') {
      if (!Array.isArray(owner)) return undefined;
      if (key < 0) k = owner.length + key;
    }
    var has = Object.prototype.hasOwnProperty.call(owner, k);
    if (step.op !== undefined && !has) return undefined;
    var want = step.not !== true;
    var v = owner[k];
    var outcome;
    switch (step.op) {
      case '==': outcome = (v === step.rval) === want; break;
      case '!=': outcome = (v !== step.rval) === want; break;
      case '<': outcome = (v < step.rval) === want; break;
      case '<=': outcome = (v <= step.rval) === want; break;
      case '>': outcome = (v > step.rval) === want; break;
      case '>=': outcome = (v >= step.rval) === want; break;
      case '^=': outcome = String(v).indexOf(step.rval) === 0 === want; break;
      case '$=': {
        var sv = String(v);
        var suffix = String(step.rval);
        outcome = (sv.length >= suffix.length && sv.slice(sv.length - suffix.length) === suffix) === want;
        break;
      }
      case '*=': outcome = (String(v).indexOf(step.rval) !== -1) === want; break;
      case '=~': outcome = step.re.test(String(v)) === want; break;
      case '!~': outcome = step.re.test(String(v)) !== want; break;
      default: outcome = has === want; break;
    }
    return outcome ? k : undefined;
  }

  function modify(compiled, obj, key) {
    var rval = compiled.rval;
    if (typeof rval === 'string') rval = rval.replace('${now}', String(Date.now()));
    switch (compiled.modify) {
      case '+': {
        var target = obj[key];
        if (!isContainer(rval) || !isContainer(target) || Array.isArray(target)) return;
        var keys = natives.keys(rval);
        for (var i = 0; i < keys.length; i++) target[keys[i]] = rval[keys[i]];
        return;
      }
      case 'repl': {
        var text = obj[key];
        if (typeof text !== 'string' || !compiled.re) return;
        obj[key] = text.replace(compiled.re, compiled.rval.replacement === undefined ? '' : String(compiled.rval.replacement));
        return;
      }
      default:
        obj[key] = rval;
    }
  }

  /**
   * Compiles [query]. The result's `test(root)` tells whether the query
   * selects anything (without modifying [root]); `apply(root)` edits [root]
   * in place and returns it (or its replacement), or undefined when the
   * query selected nothing.
   */
  function create(query) {
    var q = String(query === undefined ? '' : query);
    var v2 = false;
    if (q.indexOf('v2:') === 0) { v2 = true; q = q.slice(3); }
    var r = compile(q, 0, v2, false);
    if (r === undefined) return INVALID;
    var compiled = { steps: r.steps, modify: undefined, rval: undefined, re: null };
    var hasValue = false;
    if (r.i !== q.length) {
      var tail = q.slice(r.i);
      var raw;
      if (/^=repl\(.+\)$/.test(tail)) { compiled.modify = 'repl'; raw = tail.slice(6, -1); }
      else if (tail.charAt(0) === '=') raw = tail.slice(1);
      else if (tail.slice(0, 2) === '+=') { compiled.modify = '+'; raw = tail.slice(2); }
      else return INVALID;
      try { compiled.rval = natives.jsonParse(raw); } catch (e) { return INVALID; }
      if (compiled.modify === 'repl') {
        var spec = compiled.rval;
        if (!isContainer(spec)) return INVALID;
        try {
          compiled.re = spec.regex !== undefined
            ? new natives.RegExp(spec.regex, spec.flags || '')
            : new natives.RegExp(H.escapeRegex(String(spec.pattern)));
        } catch (e) { return INVALID; }
      }
      hasValue = true;
    }
    // A bare `$` selects the whole document: only assignments may target it.
    if (r.steps.length <= 1 && (!hasValue || r.steps[0].mv !== ROOT)) return INVALID;
    return {
      valid: true,
      hasValue: hasValue,
      test: function (root) {
        return new Evaluator(root).evaluate(compiled.steps, ['$']).length !== 0;
      },
      apply: function (root) {
        var ev = new Evaluator(root);
        var paths = ev.evaluate(compiled.steps, ['$']);
        if (paths.length === 0) return undefined;
        for (var i = paths.length - 1; i >= 0; i--) {
          var at;
          try { at = ev.resolve(paths[i]); } catch (e) { continue; }
          if (!isContainer(at.obj)) continue;
          if (hasValue) modify(compiled, at.obj, at.key);
          else if (Array.isArray(at.obj) && typeof at.key === 'number') at.obj.splice(at.key, 1);
          else delete at.obj[at.key];
        }
        return ev.root.$ === undefined ? null : ev.root.$;
      }
    };
  }

  return { create: create };
})();
