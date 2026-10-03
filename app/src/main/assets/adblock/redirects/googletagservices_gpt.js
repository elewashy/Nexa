(function () {
  'use strict';
  // Google Publisher Tag surrogate: the API exists and every call is a no-op.
  var noop = function () {};
  var noopThis = function () { return this; };
  var noopNull = function () { return null; };
  var noopArray = function () { return []; };
  var noopString = function () { return ''; };
  var slot = function () {
    return {
      addService: noopThis, clearCategoryExclusions: noopThis, clearTargeting: noopThis,
      defineSizeMapping: noopThis, get: noopNull, getAdUnitPath: noopArray, getAttributeKeys: noopArray,
      getCategoryExclusions: noopArray, getDomId: noopString, getResponseInformation: noopNull,
      getSlotElementId: noopString, getSlotId: noopThis, getTargeting: noopArray, getTargetingKeys: noopArray,
      set: noopThis, setCategoryExclusion: noopThis, setClickUrl: noopThis, setCollapseEmptyDiv: noopThis,
      setConfig: noopThis, setForceSafeFrame: noopThis, setSafeFrameConfig: noopThis, setTargeting: noopThis,
      updateTargetingFromMap: noopThis
    };
  };
  var pubads = {
    addEventListener: noopThis, removeEventListener: noopThis, clear: noop, clearCategoryExclusions: noopThis,
    clearTagForChildDirectedTreatment: noopThis, clearTargeting: noopThis, collapseEmptyDivs: noopThis,
    defineOutOfPagePassback: function () { return slot(); }, definePassback: function () { return slot(); },
    disableInitialLoad: noop, display: noop, enableAsyncRendering: noop, enableLazyLoad: noop,
    enableSingleRequest: noop, enableSyncRendering: noop, enableVideoAds: noop, get: noopNull,
    getAttributeKeys: noopArray, getTargeting: noopArray, getTargetingKeys: noopArray, getSlots: noopArray,
    isInitialLoadDisabled: function () { return false; }, refresh: noop, set: noopThis,
    setCategoryExclusion: noopThis, setCentering: noop, setCookieOptions: noopThis, setForceSafeFrame: noopThis,
    setLocation: noopThis, setPrivacySettings: noopThis, setPublisherProvidedId: noopThis,
    setRequestNonPersonalizedAds: noopThis, setSafeFrameConfig: noopThis, setTagForChildDirectedTreatment: noopThis,
    setTargeting: noopThis, setVideoContent: noopThis, updateCorrelator: noop
  };
  var companionAds = { addEventListener: noopThis, enableSyncLoading: noop, setRefreshUnfilledSlots: noop };
  var content = { addEventListener: noopThis, setContent: noop };
  var gpt = window.googletag || {};
  var cmd = gpt.cmd || [];
  gpt.apiReady = true;
  gpt.pubadsReady = true;
  gpt.companionAds = function () { return companionAds; };
  gpt.content = function () { return content; };
  gpt.defineOutOfPageSlot = function () { return slot(); };
  gpt.defineSlot = function () { return slot(); };
  gpt.destroySlots = noop;
  gpt.disablePublisherConsole = noop;
  gpt.display = noop;
  gpt.enableServices = noop;
  gpt.getVersion = noopString;
  gpt.pubads = function () { return pubads; };
  gpt.setAdIframeTitle = noop;
  gpt.setConfig = noop;
  gpt.sizeMapping = function () { return { addSize: noopThis, build: noopNull }; };
  gpt.cmd = { push: function (fn) { try { fn(); } catch (e) { /* ignore */ } return 1; } };
  window.googletag = gpt;
  for (var i = 0; i < cmd.length; i++) { try { cmd[i](); } catch (e) { /* ignore */ } }
})();
