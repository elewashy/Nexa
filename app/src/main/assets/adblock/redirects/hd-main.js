(function () {
  'use strict';
  // Hiro/svonm hd-main.js surrogate: its minified globals expose only no-op methods.
  var noop = function () {};
  var stub = typeof Proxy === 'function'
    ? new Proxy({}, { get: function (target, prop) { return prop in target ? target[prop] : noop; } })
    : {};
  window.L = window.L || stub;
  window.J = window.J || stub;
})();
