(function () {
  'use strict';
  // Sensors Data analytics surrogate: every SDK method is a no-op.
  var noop = function () {};
  var stub = typeof Proxy === 'function'
    ? new Proxy({}, {
      get: function (target, prop) {
        if (prop in target) return target[prop];
        return prop === 'para' ? {} : noop;
      }
    })
    : { init: noop, track: noop, quick: noop, login: noop, logout: noop, register: noop, setProfile: noop };
  window.sensorsDataAnalytic201505 = stub;
  window.sensors = window.sensors || stub;
})();
