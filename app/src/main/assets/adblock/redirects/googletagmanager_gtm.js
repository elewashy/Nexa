(function () {
  'use strict';
  // GTM surrogate: dataLayer events still invoke their callbacks so pages don't stall.
  var noop = function () {};
  window.ga = window.ga || noop;
  var dl = window.dataLayer;
  if (!dl || typeof dl.push !== 'function') return;
  if (dl.hide && typeof dl.hide.end === 'function') { try { dl.hide.end(); } catch (e) { /* ignore */ } }
  var fire = function (o) {
    if (o && typeof o === 'object' && typeof o.eventCallback === 'function') setTimeout(o.eventCallback, 1);
  };
  for (var i = 0; i < dl.length; i++) fire(dl[i]);
  var push = dl.push;
  dl.push = function (o) { fire(o); return push.apply(this, arguments); };
})();
