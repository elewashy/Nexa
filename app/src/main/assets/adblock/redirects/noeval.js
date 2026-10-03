(function () {
  'use strict';
  // Disables eval() for the page (string evaluation returns undefined).
  window.eval = new Proxy(window.eval, { apply: function () { return undefined; } });
})();
