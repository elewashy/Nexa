(function () {
  'use strict';
  // NitroPay surrogate: ad slots are created as inert placeholders.
  var noop = function () {};
  var queued = window.nitroAds && Array.isArray(window.nitroAds.queue) ? window.nitroAds.queue : [];
  window.nitroAds = {
    loaded: true,
    queue: [],
    createAd: function () { return Promise.resolve({ onNavigate: noop, refresh: noop }); },
    addUserToken: noop,
    removeUserToken: noop,
    clearUserTokens: noop,
    hasUserToken: function () { return false; },
    stop: noop
  };
  window.nitroAds.queue.push = function () { return 0; };
  for (var i = 0; i < queued.length; i++) {
    var entry = queued[i];
    if (entry && entry[0] === 'createAd') window.nitroAds.createAd();
  }
})();
