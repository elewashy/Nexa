(function () {
  'use strict';
  // BlockAdBlock v4 (obfuscated bundle) surrogate: neutralises the detector globals.
  var noop = function () {};
  var stub = function () {};
  stub.prototype = { check: noop, clearEvent: noop, emitEvent: noop, on: function () { return this; },
    onDetected: function () { return this; }, onNotDetected: function (fn) { if (typeof fn === 'function') setTimeout(fn, 1); return this; },
    setOption: function () { return this; } };
  ['BlockAdBlock', 'blockAdBlock', 'sniffAdBlock', 'SniffAdBlock'].forEach(function (name) {
    try { Object.defineProperty(window, name, { configurable: true, value: name.charAt(0) === name.charAt(0).toUpperCase() ? stub : new stub() }); } catch (e) { /* ignore */ }
  });
})();
