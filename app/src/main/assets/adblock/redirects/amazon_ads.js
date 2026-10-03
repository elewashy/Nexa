(function () {
  'use strict';
  // Amazon amzn_ads.js surrogate: every API call is a no-op.
  if (window.amznads) return;
  var noop = function () {};
  var names = [
    'appendScriptTag', 'appendTargetingToAdServerUrl', 'appendTargetingToQueryString', 'clearTargetingFromGPTAsync',
    'doAllTasks', 'doGetAdsAsync', 'doTask', 'detectIframeAndGetURL', 'getAds', 'getAdsAsync', 'getAdForSlot',
    'getAdsCallback', 'getDisplayAds', 'getDisplayAdsAsync', 'getDisplayAdsCallback', 'getKeys', 'getReferrerURL',
    'getScriptSource', 'getTargeting', 'getTokens', 'getValidMilliseconds', 'getVideoAds', 'getVideoAdsAsync',
    'getVideoAdsCallback', 'handleCallBack', 'hasAds', 'renderAd', 'saveAds', 'setTargeting',
    'setTargetingForGPTAsync', 'setTargetingForGPTSync', 'tryGetAdsAsync', 'updateAds'
  ];
  var amznads = {};
  for (var i = 0; i < names.length; i++) amznads[names[i]] = noop;
  window.amznads = amznads;
  window.amzn_ads = window.amzn_ads || noop;
  window.aax_write = window.aax_write || noop;
  window.aax_render_ad = window.aax_render_ad || noop;
})();
