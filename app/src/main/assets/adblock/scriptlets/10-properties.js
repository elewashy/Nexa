/* Property traps: set-constant, abort-on-property-*, abort-current-script, … */

function setConstantImpl(chain, rawValue, trusted, extra) {
  if (!chain) return;
  var value = H.constantValue(rawValue, trusted);
  if (value === H.SKIP) return;
  var as = extra && extra.as;
  if (as === 'function') { var fnValue = value; value = function () { return fnValue; }; }
  else if (as === 'callback') { var cbValue = value; value = function () { return function () { return cbValue; }; }; }
  else if (as === 'resolved') value = H.natives.Promise.resolve(value);
  else if (as === 'rejected') value = H.natives.Promise.reject(value);

  // uBO `mustAbort`: an untrusted filter never replaces an existing value
  // with a defined value of another type (avoids breaking unrelated code).
  var mustAbort = function (current) {
    return !trusted && current !== undefined && current !== null &&
      value !== undefined && value !== null && typeof current !== typeof value;
  };

  H.trapChain(window, chain, function (owner, prop) {
    var desc = H.natives.getOwnPropertyDescriptor(owner, prop);
    if (desc && desc.configurable === false) {
      if (desc.writable) { try { owner[prop] = value; } catch (e) { /* ignore */ } }
      return;
    }
    if (desc && 'value' in desc && mustAbort(desc.value)) return;
    var previousSetter = desc && desc.set;
    try {
      H.define(owner, prop, {
        configurable: true,
        enumerable: desc ? desc.enumerable : true,
        get: function () { return value; },
        set: function (v) { if (previousSetter) { try { previousSetter.call(this, v); } catch (e) { /* ignore */ } } }
      });
    } catch (e) { /* frozen owner */ }
  });
}

S['set-constant'] = function (chain, value) {
  setConstantImpl(chain, value, false, H.extraArgs(arguments, 2));
};

S['trusted-set-constant'] = function (chain, value) {
  setConstantImpl(chain, value, true, H.extraArgs(arguments, 2));
};

function abortOnProperty(chain, mode) {
  if (!chain) return;
  H.installErrorHandler();
  var thrower = function () { throw H.abortError(); };
  H.trapChain(window, chain, function (owner, prop) {
    var desc = H.natives.getOwnPropertyDescriptor(owner, prop);
    if (desc && desc.configurable === false) return;
    var value = desc && 'value' in desc ? desc.value : owner[prop];
    try {
      H.define(owner, prop, mode === 'read' ? {
        configurable: true,
        get: thrower,
        set: H.noopFunc
      } : {
        configurable: true,
        get: function () { return value; },
        set: thrower
      });
    } catch (e) { /* ignore */ }
  });
}

S['abort-on-property-read'] = function (chain) { abortOnProperty(chain, 'read'); };
S['abort-on-property-write'] = function (chain) { abortOnProperty(chain, 'write'); };

/**
 * Throws when [target] is accessed by a script whose text (or src) matches
 * [needle]; optional [context] restricts by surrounding script text.
 */
S['abort-current-script'] = function (target, needle, context) {
  if (!target) return;
  H.installErrorHandler();
  var reNeedle = H.matcher(needle || '', true);
  var reContext = H.matcher(context || '', true);
  var thisScript = document.currentScript;
  var shouldAbort = function () {
    var el = document.currentScript;
    if (!(el instanceof HTMLScriptElement) || el === thisScript) return false;
    var text = H.scriptText(el);
    return reNeedle.test(text) && reContext.test(text);
  };
  // The chain's last segment is trapped; intermediate objects are reached lazily.
  H.trapChain(window, target, function (owner, prop) {
    var desc = H.natives.getOwnPropertyDescriptor(owner, prop);
    if (desc && desc.configurable === false) return;
    var getter = desc && desc.get;
    var setter = desc && desc.set;
    var value = desc && 'value' in desc ? desc.value : undefined;
    try {
      H.define(owner, prop, {
        configurable: true,
        get: function () {
          if (shouldAbort()) throw H.abortError();
          return getter ? getter.call(this) : value;
        },
        set: function (v) {
          if (shouldAbort()) throw H.abortError();
          if (setter) setter.call(this, v); else value = v;
        }
      });
    } catch (e) { /* ignore */ }
  });
};

S['abort-on-stack-trace'] = function (chain, needle) {
  if (!chain) return;
  H.installErrorHandler();
  var reNeedle = H.matcher(needle || '', true);
  H.trapChain(window, chain, function (owner, prop) {
    var desc = H.natives.getOwnPropertyDescriptor(owner, prop);
    if (desc && desc.configurable === false) return;
    var getter = desc && desc.get;
    var setter = desc && desc.set;
    var value = desc && 'value' in desc ? desc.value : undefined;
    try {
      H.define(owner, prop, {
        configurable: true,
        get: function () {
          if (H.matchesStack(reNeedle)) throw H.abortError();
          return getter ? getter.call(this) : value;
        },
        set: function (v) {
          if (H.matchesStack(reNeedle)) throw H.abortError();
          if (setter) setter.call(this, v); else value = v;
        }
      });
    } catch (e) { /* ignore */ }
  });
};

S['noeval'] = function () {
  H.proxyApply('eval', function () { return undefined; });
};

S['noeval-if'] = function (needle) {
  var m = H.matcher(needle || '', true);
  H.proxyApply('eval', function (ctx) {
    if (m.test(String(ctx.callArgs[0]))) return undefined;
    return ctx.reflect();
  });
};

/**
 * trusted-replace-argument(fn, argpos, argraw[, condition, pattern]):
 * replaces argument [argpos] of calls to [fn] with [argraw] (JSON or token).
 */
S['trusted-replace-argument'] = function (chain, argpos, argraw) {
  if (!chain) return;
  var extra = H.extraArgs(arguments, 3);
  var pos = parseInt(argpos || '0', 10);
  if (isNaN(pos)) return;
  var replacement = H.constantValue(argraw, true);
  if (replacement === H.SKIP) return;
  var condition = H.matcher(extra.condition || '', true);
  H.proxyApply(chain, function (ctx) {
    var args = ctx.callArgs;
    var index = pos < 0 ? args.length + pos : pos;
    if (index >= 0 && index < args.length && condition.test(String(args[index]))) args[index] = replacement;
    return ctx.reflect();
  });
};
