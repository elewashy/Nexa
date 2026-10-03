(function () {
  'use strict';
  // AdSense surrogate: `adsbygoogle.push()` succeeds and ad slots are marked filled-but-empty.
  var init = function () {
    var slots = document.querySelectorAll('.adsbygoogle');
    for (var i = 0; i < slots.length; i++) {
      var slot = slots[i];
      if (slot.getAttribute('data-adsbygoogle-status') === 'done') continue;
      slot.setAttribute('data-adsbygoogle-status', 'done');
      var frame = document.createElement('iframe');
      frame.style.cssText = 'display:none!important;width:0;height:0;border:0';
      slot.appendChild(frame);
    }
  };
  var queue = window.adsbygoogle;
  var stub = {
    loaded: true,
    push: function () { init(); return 0; },
    length: 0
  };
  try {
    Object.defineProperty(window, 'adsbygoogle', { configurable: true, get: function () { return stub; }, set: function () {} });
  } catch (e) { window.adsbygoogle = stub; }
  if (Array.isArray(queue)) init();
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once: true });
})();
