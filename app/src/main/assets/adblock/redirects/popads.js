(function () {
  'use strict';
  // PopAds surrogate: the API exists, nothing pops.
  var stub = { show: function () {}, hide: function () {} };
  try {
    Object.defineProperty(window, 'PopAds', { configurable: true, get: function () { return stub; }, set: function () {} });
    Object.defineProperty(window, 'popns', { configurable: true, get: function () { return stub; }, set: function () {} });
  } catch (e) { /* ignore */ }
})();
