(function () {
  'use strict';
  // analytics.js / gtag surrogate: commands are accepted, hit callbacks still fire.
  var noop = function () {};
  var tracker = { get: noop, set: noop, send: noop };
  var runCallback = function (args) {
    for (var i = 0; i < args.length; i++) {
      var a = args[i];
      if (a && typeof a === 'object') {
        var cb = a.hitCallback || a.event_callback;
        if (typeof cb === 'function') { try { setTimeout(cb, 1); } catch (e) { /* ignore */ } }
      }
    }
  };
  var ga = function () {
    var args = arguments;
    if (typeof args[0] === 'function') { try { args[0](tracker); } catch (e) { /* ignore */ } return; }
    runCallback(args);
  };
  ga.create = function () { return tracker; };
  ga.getByName = function () { return tracker; };
  ga.getAll = function () { return [tracker]; };
  ga.remove = noop;
  ga.loaded = true;
  var name = window.GoogleAnalyticsObject || 'ga';
  var queue = window[name] && window[name].q;
  window[name] = ga;
  if (Array.isArray(queue)) for (var i = 0; i < queue.length; i++) ga.apply(null, queue[i]);
  var dl = window.dataLayer;
  if (dl && typeof dl.push === 'function') {
    dl.hide && dl.hide.end && setTimeout(dl.hide.end, 1);
    var push = dl.push;
    dl.push = function (o) {
      if (o && typeof o.eventCallback === 'function') setTimeout(o.eventCallback, 1);
      return push.apply(this, arguments);
    };
  }
  if (typeof window.gtag !== 'function') window.gtag = function () { runCallback(arguments); };
})();
