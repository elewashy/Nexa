(function () {
  'use strict';
  // Legacy ga.js surrogate (_gaq / _gat).
  var noop = function () {};
  var tracker = {};
  ['_addIgnoredOrganic', '_addIgnoredRef', '_addItem', '_addOrganic', '_addTrans', '_clearIgnoredOrganic',
    '_clearIgnoredRef', '_clearOrganic', '_cookiePathCopy', '_deleteCustomVar', '_getName', '_setAccount',
    '_getAccount', '_getClientInfo', '_getDetectFlash', '_getDetectTitle', '_getLinkerUrl', '_getLocalGifPath',
    '_getServiceMode', '_getVersion', '_getVisitorCustomVar', '_initData', '_link', '_linkByPost',
    '_setAllowAnchor', '_setAllowHash', '_setAllowLinker', '_setCampContentKey', '_setCampMediumKey',
    '_setCampNameKey', '_setCampNOKey', '_setCampSourceKey', '_setCampTermKey', '_setCampaignCookieTimeout',
    '_setCampaignTrack', '_setClientInfo', '_setCookiePath', '_setCookiePersistence', '_setCookieTimeout',
    '_setCustomVar', '_setDetectFlash', '_setDetectTitle', '_setDomainName', '_setLocalGifPath',
    '_setLocalRemoteServerMode', '_setLocalServerMode', '_setReferrerOverride', '_setRemoteServerMode',
    '_setSampleRate', '_setSessionTimeout', '_setSiteSpeedSampleRate', '_setSessionCookieTimeout', '_setVar',
    '_setVisitorCookieTimeout', '_trackEvent', '_trackPageLoadTime', '_trackPageview', '_trackSocial',
    '_trackTiming', '_trackTrans', '_visitCode'
  ].forEach(function (name) { tracker[name] = noop; });
  tracker._getLinkerUrl = function (url) { return url; };
  var gaq = {
    push: function () {
      for (var i = 0; i < arguments.length; i++) {
        var cmd = arguments[i];
        if (typeof cmd === 'function') { try { cmd(); } catch (e) { /* ignore */ } continue; }
        if (Array.isArray(cmd) && cmd[0] === '_link' && typeof cmd[1] === 'string') {
          try { window.location.assign(cmd[1]); } catch (e) { /* ignore */ }
        }
      }
      return 0;
    }
  };
  var old = window._gaq;
  window._gat = { _createTracker: function () { return tracker; }, _getTracker: function () { return tracker; },
    _getTrackerByName: function () { return tracker; }, _anonymizeIp: noop, _forceSSL: noop };
  window._gaq = gaq;
  if (Array.isArray(old)) gaq.push.apply(gaq, old);
})();
