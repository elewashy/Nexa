(function () {
  'use strict';
  // PopAds placeholder: the globals exist (inert) so loader checks pass.
  try {
    delete window.PopAds;
    delete window.popns;
    Object.defineProperties(window, { PopAds: { value: {} }, popns: { value: {} } });
  } catch (e) { /* ignore */ }
})();
