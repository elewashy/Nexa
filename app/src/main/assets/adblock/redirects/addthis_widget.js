(function () {
  'use strict';
  // AddThis surrogate: the share-widget API exists and does nothing.
  var noop = function () {};
  window.addthis = {
    addEventListener: noop,
    button: noop,
    counter: noop,
    init: noop,
    layers: noop,
    ready: noop,
    sharecounters: { getShareCounts: noop },
    toolbox: noop,
    update: noop
  };
})();
