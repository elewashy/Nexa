(function () {
  'use strict';
  // AdThrive ads.min.js surrogate: queued commands run, nothing loads, no ad-block report.
  var noop = function () {};
  var adthrive = window.adthrive = window.adthrive || {};
  var run = function (fn) { if (typeof fn === 'function') { try { fn(); } catch (e) { /* ignore */ } } };
  var queued = Array.isArray(adthrive.cmd) ? adthrive.cmd : [];
  adthrive.cmd = { push: function () { for (var i = 0; i < arguments.length; i++) run(arguments[i]); return 0; } };
  for (var i = 0; i < queued.length; i++) run(queued[i]);
  adthrive.siteAds = adthrive.siteAds || {};
  adthrive.abd = false;
  adthrive.on = noop;
  adthrive.off = noop;
})();
