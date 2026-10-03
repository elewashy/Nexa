(function () {
  'use strict';
  // BlockAdBlock surrogate: always reports that no ad blocker is present.
  var BlockAdBlock = function () {};
  BlockAdBlock.prototype = {
    check: function () { this.emitEvent(); return true; },
    clearEvent: function () {},
    emitEvent: function () { var self = this; setTimeout(function () { (self._nd || []).forEach(function (fn) { try { fn(); } catch (e) { /* ignore */ } }); }, 1); return this; },
    on: function (detected, fn) { if (!detected) this.onNotDetected(fn); return this; },
    onDetected: function () { return this; },
    onNotDetected: function (fn) { (this._nd = this._nd || []).push(fn); return this; },
    setOption: function () { return this; }
  };
  var instance = new BlockAdBlock();
  window.BlockAdBlock = BlockAdBlock;
  window.blockAdBlock = instance;
  instance.emitEvent();
})();
