(function () {
  'use strict';
  // Google Content Experiments surrogate: always the original variation.
  var noop = function () {};
  window.cxApi = {
    chooseVariation: function () { return 0; },
    getChosenVariation: noop,
    setAllowHash: noop,
    setChosenVariation: noop,
    setCookiePath: noop,
    setDomainName: noop
  };
})();
