(function () {
  'use strict';
  // Google IMA SDK (v3) surrogate. Requesting ads yields an ads manager that
  // immediately reports "all ads completed", so players resume content
  // playback instead of waiting for an ad that never loads.
  if (window.google && window.google.ima && window.google.ima.AdsLoader) return;
  var noop = function () {};

  function EventTargetLike() { this.listeners = {}; }
  EventTargetLike.prototype.addEventListener = function (types, fn, capture, scope) {
    var list = Array.isArray(types) ? types : [types];
    for (var i = 0; i < list.length; i++) {
      (this.listeners[list[i]] = this.listeners[list[i]] || []).push({ fn: fn, scope: scope });
    }
  };
  EventTargetLike.prototype.removeEventListener = function (types, fn) {
    var list = Array.isArray(types) ? types : [types];
    for (var i = 0; i < list.length; i++) {
      var ls = this.listeners[list[i]];
      if (ls) this.listeners[list[i]] = ls.filter(function (l) { return l.fn !== fn; });
    }
  };
  EventTargetLike.prototype._dispatch = function (event) {
    var ls = (this.listeners[event.type] || []).slice();
    for (var i = 0; i < ls.length; i++) {
      try {
        if (typeof ls[i].fn === 'function') ls[i].fn.call(ls[i].scope || null, event);
        else if (ls[i].fn && typeof ls[i].fn.handleEvent === 'function') ls[i].fn.handleEvent(event);
      } catch (e) { /* ignore */ }
    }
  };

  var AdEventType = {
    ALL_ADS_COMPLETED: 'allAdsCompleted', CLICK: 'click', COMPLETE: 'complete',
    CONTENT_PAUSE_REQUESTED: 'contentPauseRequested', CONTENT_RESUME_REQUESTED: 'contentResumeRequested',
    FIRST_QUARTILE: 'firstQuartile', LOADED: 'loaded', MIDPOINT: 'midpoint', PAUSED: 'pause',
    RESUMED: 'resume', SKIPPED: 'skip', STARTED: 'start', THIRD_QUARTILE: 'thirdQuartile',
    USER_CLOSE: 'userClose', VOLUME_CHANGED: 'volumeChange', AD_BREAK_READY: 'adBreakReady',
    AD_METADATA: 'adMetadata', LOG: 'log', IMPRESSION: 'impression', DURATION_CHANGE: 'durationChange'
  };

  function AdEvent(type) { this.type = type; }
  AdEvent.prototype.getAd = function () { return null; };
  AdEvent.prototype.getAdData = function () { return {}; };
  AdEvent.Type = AdEventType;

  function AdsManager() { EventTargetLike.call(this); this.volume = 1; }
  AdsManager.prototype = Object.create(EventTargetLike.prototype);
  AdsManager.prototype.constructor = AdsManager;
  ['collapse', 'configureAdsManager', 'destroy', 'discardAdBreak', 'expand', 'focus', 'pause', 'resize',
    'resume', 'skip', 'stop', 'updateAdsRenderingSettings', 'clicked'].forEach(function (m) { AdsManager.prototype[m] = noop; });
  AdsManager.prototype.init = noop;
  AdsManager.prototype.isCustomClickTrackingUsed = function () { return false; };
  AdsManager.prototype.isCustomPlaybackUsed = function () { return false; };
  AdsManager.prototype.getAdSkippableState = function () { return false; };
  AdsManager.prototype.getCuePoints = function () { return []; };
  AdsManager.prototype.getCurrentAd = function () { return null; };
  AdsManager.prototype.getRemainingTime = function () { return 0; };
  AdsManager.prototype.getVolume = function () { return this.volume; };
  AdsManager.prototype.setVolume = function (v) { this.volume = v; };
  AdsManager.prototype.start = function () {
    var self = this;
    setTimeout(function () {
      self._dispatch(new AdEvent(AdEventType.CONTENT_RESUME_REQUESTED));
      self._dispatch(new AdEvent(AdEventType.ALL_ADS_COMPLETED));
    }, 5);
  };

  function AdsManagerLoadedEvent(manager) { this.type = 'adsManagerLoaded'; this.manager = manager; }
  AdsManagerLoadedEvent.prototype.getAdsManager = function () { return this.manager; };
  AdsManagerLoadedEvent.prototype.getUserRequestContext = function () { return {}; };
  AdsManagerLoadedEvent.Type = { ADS_MANAGER_LOADED: 'adsManagerLoaded' };

  function AdError(message) { this.message = message; }
  AdError.prototype.getErrorCode = function () { return 1009; };
  AdError.prototype.getMessage = function () { return this.message; };
  AdError.prototype.getType = function () { return 'adLoadError'; };
  AdError.prototype.getVastErrorCode = function () { return 303; };
  AdError.prototype.getInnerError = function () { return null; };
  AdError.prototype.toString = function () { return 'AdError 1009: ' + this.message; };
  AdError.ErrorCode = {};
  AdError.Type = { AD_LOAD: 'adLoadError', AD_PLAY: 'adPlayError' };

  function AdErrorEvent(error) { this.type = 'adError'; this.error = error; }
  AdErrorEvent.prototype.getError = function () { return this.error; };
  AdErrorEvent.prototype.getUserRequestContext = function () { return {}; };
  AdErrorEvent.Type = { AD_ERROR: 'adError' };

  function AdsLoader() { EventTargetLike.call(this); this.settings = new ImaSdkSettings(); }
  AdsLoader.prototype = Object.create(EventTargetLike.prototype);
  AdsLoader.prototype.constructor = AdsLoader;
  AdsLoader.prototype.contentComplete = noop;
  AdsLoader.prototype.destroy = noop;
  AdsLoader.prototype.getSettings = function () { return this.settings; };
  AdsLoader.prototype.getVersion = function () { return '3.0'; };
  AdsLoader.prototype.requestAds = function () {
    var self = this;
    setTimeout(function () { self._dispatch(new AdsManagerLoadedEvent(new AdsManager())); }, 5);
  };

  function AdDisplayContainer() {}
  AdDisplayContainer.prototype.initialize = noop;
  AdDisplayContainer.prototype.destroy = noop;

  function AdsRequest() {}
  AdsRequest.prototype.setAdWillAutoPlay = noop;
  AdsRequest.prototype.setAdWillPlayMuted = noop;
  AdsRequest.prototype.setContinuousPlayback = noop;

  function AdsRenderingSettings() {}

  function ImaSdkSettings() {}
  ['setAutoPlayAdBreaks', 'setCompanionBackfill', 'setCookiesEnabled', 'setDisableCustomPlaybackForIOS10Plus',
    'setFeatureFlags', 'setLocale', 'setNumRedirects', 'setPlayerType', 'setPlayerVersion', 'setPpid',
    'setSessionId', 'setVpaidAllowed', 'setVpaidMode'].forEach(function (m) { ImaSdkSettings.prototype[m] = noop; });
  ImaSdkSettings.prototype.getLocale = function () { return 'en'; };
  ImaSdkSettings.prototype.getNumRedirects = function () { return 0; };
  ImaSdkSettings.prototype.getPlayerType = function () { return ''; };
  ImaSdkSettings.prototype.getPlayerVersion = function () { return ''; };
  ImaSdkSettings.VpaidMode = { DISABLED: 0, ENABLED: 1, INSECURE: 2 };
  ImaSdkSettings.CompanionBackfillMode = { ALWAYS: 'always', ON_MASTER_AD: 'on_master_ad' };

  function CompanionAdSelectionSettings() {}
  CompanionAdSelectionSettings.CreativeType = { ALL: 'All', FLASH: 'Flash', IMAGE: 'Image' };
  CompanionAdSelectionSettings.ResourceType = { ALL: 'All', HTML: 'Html', IFRAME: 'IFrame', STATIC: 'Static' };
  CompanionAdSelectionSettings.SizeCriteria = { IGNORE: 'IgnoreSize', SELECT_EXACT_MATCH: 'SelectExactMatch', SELECT_NEAR_MATCH: 'SelectNearMatch' };

  window.google = window.google || {};
  window.google.ima = {
    AdDisplayContainer: AdDisplayContainer,
    AdError: AdError,
    AdErrorEvent: AdErrorEvent,
    AdEvent: AdEvent,
    AdsLoader: AdsLoader,
    AdsManager: AdsManager,
    AdsManagerLoadedEvent: AdsManagerLoadedEvent,
    AdsRenderingSettings: AdsRenderingSettings,
    AdsRequest: AdsRequest,
    CompanionAdSelectionSettings: CompanionAdSelectionSettings,
    ImaSdkSettings: ImaSdkSettings,
    OmidAccessMode: { DOMAIN: 'domain', FULL: 'full', LIMITED: 'limited' },
    UiElements: { AD_ATTRIBUTION: 'adAttribution', COUNTDOWN: 'countdown' },
    ViewMode: { FULLSCREEN: 'fullscreen', NORMAL: 'normal' },
    settings: new ImaSdkSettings(),
    VERSION: '3.517.2'
  };
})();
