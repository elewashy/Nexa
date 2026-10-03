(function () {
  'use strict';
  // FuckAdBlock 3.2.0 surrogate: never detects, `onNotDetected` callbacks run.
  var Fab = function () {};
  Fab.prototype = {
    check: function () { this.emitEvent(false); return true; },
    clearEvent: function () {},
    emitEvent: function () { var self = this; setTimeout(function () { (self._nd || []).forEach(function (fn) { try { fn(); } catch (e) { /* ignore */ } }); }, 1); return this; },
    on: function (detected, fn) { if (!detected) this.onNotDetected(fn); return this; },
    onDetected: function () { return this; },
    onNotDetected: function (fn) { (this._nd = this._nd || []).push(fn); return this; },
    setOption: function () { return this; },
    options: { set: function () { return this; }, get: function () { return this; } }
  };
  var instance = new Fab();
  ['FuckAdBlock', 'BlockAdBlock'].forEach(function (name) { window[name] = Fab; });
  ['fuckAdBlock', 'blockAdBlock'].forEach(function (name) { window[name] = instance; });
  instance.emitEvent(false);
})();
