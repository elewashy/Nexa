(function () {
  'use strict';
  // FingerprintJS2 surrogate: returns a constant, non-identifying fingerprint.
  var hash = '';
  for (var i = 0; i < 32; i++) hash += 'abcdef0123456789'.charAt(Math.floor(Math.random() * 16));
  var Fingerprint2 = function () {};
  Fingerprint2.get = function (opts, cb) {
    if (typeof opts === 'function') cb = opts;
    setTimeout(function () { if (typeof cb === 'function') cb([]); }, 1);
  };
  Fingerprint2.getPromise = function () { return Promise.resolve([]); };
  Fingerprint2.getV18 = function (opts, cb) {
    if (typeof opts === 'function') cb = opts;
    setTimeout(function () { if (typeof cb === 'function') cb(hash, []); }, 1);
  };
  Fingerprint2.x64hash128 = function () { return hash; };
  Fingerprint2.prototype = { get: function (cb) { setTimeout(function () { if (typeof cb === 'function') cb(hash, []); }, 1); } };
  window.Fingerprint2 = Fingerprint2;
})();
