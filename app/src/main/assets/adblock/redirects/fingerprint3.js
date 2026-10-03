(function () {
  'use strict';
  // FingerprintJS v3 surrogate: a random, per-page visitor id.
  var visitorId = '';
  for (var i = 0; i < 8; i++) visitorId += (Math.random() * 0x10000 + 0x1000 | 0).toString(16).slice(-4);
  var FingerprintJS = function () {};
  FingerprintJS.hashComponents = function () { return visitorId; };
  FingerprintJS.load = function () { return Promise.resolve(new FingerprintJS()); };
  FingerprintJS.prototype.get = function () { return Promise.resolve({ visitorId: visitorId, components: {}, confidence: { score: 1 } }); };
  window.FingerprintJS = FingerprintJS;
})();
