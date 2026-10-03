(function () {
  'use strict';
  // Outbrain widget surrogate: the API exists, widgets render nothing.
  var noop = function () {};
  var obr = window.OBR = window.OBR || {};
  obr.extern = { callClick: noop, callRecs: function (opts, cb) { if (typeof cb === 'function') setTimeout(function () { cb({ doc: [] }); }, 1); },
    callWhatIs: noop, closeCard: noop, closeModal: noop, closeToaster: noop, cookieSupport: noop,
    getCountOfRecs: function () { return 0; }, getStat: function () { return 0; }, imageError: noop,
    manualVideoClicked: noop, onOdbReturn: noop, onVideoClick: noop, pageScrolled: noop, rClicked: noop,
    refreshSpecificWidget: noop, refreshWidget: noop, reloadWidget: noop, researchWidget: noop,
    returnedError: noop, returnedHtmlData: noop, returnedIrdData: noop, returnedJsonData: noop,
    scrollLoad: noop, showDescription: noop, showRecInIframe: noop, userAction: noop,
    videoAvailable: noop, videoInitialized: noop, zoomLoad: noop };
})();
