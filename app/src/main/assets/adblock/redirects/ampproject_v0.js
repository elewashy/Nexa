(function () {
  'use strict';
  // AMP runtime surrogate: undo the boilerplate that hides the page until the runtime starts.
  var head = document.head;
  if (!head) return;
  var style = document.createElement('style');
  style.textContent = 'body{animation:none!important;overflow:unset!important}';
  head.appendChild(style);
})();
