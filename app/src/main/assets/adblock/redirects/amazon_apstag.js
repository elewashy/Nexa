(function () {
  'use strict';
  // Amazon Publisher Services (apstag) surrogate: bids resolve empty.
  var w = window;
  var noop = function () {};
  var queue = w.apstag && w.apstag._Q || [];
  var apstag = {
    _getSlotIdToNameMapping: noop,
    _Q: [],
    _setDisplayBids: noop,
    debug: noop,
    deleteId: noop,
    displayAds: noop,
    fetchBids: function (cfg, callback) {
      if (typeof callback === 'function') {
        var slots = cfg && Array.isArray(cfg.slots) ? cfg.slots : [];
        setTimeout(function () {
          callback(slots.map(function (s) { return { amznbid: '', amzniid: '', amznp: '', amznsz: '0x0', size: '0x0', slotID: s.slotID }; }));
        }, 1);
      }
    },
    init: noop,
    punt: noop,
    renderImp: noop,
    renewId: noop,
    rpa: noop,
    setDisplayBids: noop,
    targetingKeys: function () { return []; },
    thirdPartyData: {},
    updateId: noop,
    upa: noop
  };
  w.apstag = apstag;
  for (var i = 0; i < queue.length; i++) {
    var item = queue[i];
    if (item && item[0] === 'f' && item[1]) apstag.fetchBids(item[1][0], item[1][1]);
  }
})();
