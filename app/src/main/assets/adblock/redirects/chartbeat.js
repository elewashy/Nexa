(function () {
  'use strict';
  // Chartbeat surrogate.
  var noop = function () {};
  window.pSUPERFLY = { activity: noop, virtualPage: noop };
  window.chartbeat = window.chartbeat || noop;
})();
