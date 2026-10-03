/* Timers, events, popups and misc defusers. */

/** Delay matcher: '' = any, 'n' = exactly n, '!n' = anything but n. */
function delayMatcher(raw) {
  if (raw === undefined || raw === '') return function () { return true; };
  var negate = raw.charAt(0) === '!';
  var n = parseInt(negate ? raw.slice(1) : raw, 10);
  if (isNaN(n)) return function () { return true; };
  return function (d) { return ((d | 0) === n) !== negate; };
}

function preventTimer(name, needle, delay) {
  var m = H.matcher(needle || '', true);
  var delayOk = delayMatcher(delay);
  H.wrapFunction(window, name, function (target, thisArg, args) {
    var callback = args[0];
    if (m.test(H.fnText(callback)) && delayOk(args[1])) args[0] = H.noopFunc;
    return H.natives.Reflect.apply(target, thisArg, args);
  });
}

S['no-setTimeout-if'] = function (needle, delay) { preventTimer('setTimeout', needle, delay); };
S['no-setInterval-if'] = function (needle, delay) { preventTimer('setInterval', needle, delay); };

S['no-requestAnimationFrame-if'] = function (needle) {
  var m = H.matcher(needle || '', true);
  H.wrapFunction(window, 'requestAnimationFrame', function (target, thisArg, args) {
    if (m.test(H.fnText(args[0]))) args[0] = H.noopFunc;
    return H.natives.Reflect.apply(target, thisArg, args);
  });
};

function adjustTimer(name, needle, delay, boost) {
  var m = H.matcher(needle || '', true);
  var wanted = delay === undefined || delay === '' ? 1000 : delay === '*' ? -1 : parseInt(delay, 10);
  var factor = parseFloat(boost || '0.05');
  if (isNaN(factor) || !isFinite(factor)) factor = 0.05;
  factor = Math.min(50, Math.max(0.001, factor));
  H.wrapFunction(window, name, function (target, thisArg, args) {
    if ((wanted === -1 || (args[1] | 0) === wanted) && m.test(H.fnText(args[0]))) {
      args[1] = (args[1] | 0) * factor;
    }
    return H.natives.Reflect.apply(target, thisArg, args);
  });
}

S['adjust-setTimeout'] = function (needle, delay, boost) { adjustTimer('setTimeout', needle, delay, boost); };
S['adjust-setInterval'] = function (needle, delay, boost) { adjustTimer('setInterval', needle, delay, boost); };

S['addEventListener-defuser'] = function (type, pattern) {
  var reType = H.matcher(type || '', true);
  var rePattern = H.matcher(pattern || '', true);
  var proto = window.EventTarget && EventTarget.prototype;
  if (!proto) return;
  H.wrapFunction(proto, 'addEventListener', function (target, thisArg, args) {
    var handler = args[1];
    var text = typeof handler === 'function' ? H.fnText(handler)
      : handler && typeof handler.handleEvent === 'function' ? H.fnText(handler.handleEvent) : String(handler);
    if (reType.test(String(args[0])) && rePattern.test(text)) return undefined;
    return H.natives.Reflect.apply(target, thisArg, args);
  });
};

/** Returns a harmless stand-in for a window opened by a blocked popup. */
function decoyWindow() {
  var noop = H.noopFunc;
  var decoy = {
    closed: false,
    opener: window,
    close: function () { decoy.closed = true; },
    focus: noop,
    blur: noop,
    postMessage: noop,
    addEventListener: noop,
    removeEventListener: noop,
    document: { write: noop, writeln: noop, open: noop, close: noop },
    location: { href: '', assign: noop, replace: noop }
  };
  return decoy;
}

S['no-window-open-if'] = function (needle, delay, decoy) {
  var m = H.matcher(needle || '', true);
  H.wrapFunction(window, 'open', function (target, thisArg, args) {
    if (!m.test(args[0] === undefined ? '' : String(args[0]))) {
      return H.natives.Reflect.apply(target, thisArg, args);
    }
    if (decoy === 'blank' || decoy === 'obj') return decoyWindow();
    return delay === undefined || delay === '' ? null : decoyWindow();
  });
};

S['disable-newtab-links'] = function () {
  document.addEventListener('click', function (ev) {
    var el = ev.target;
    while (el && el.nodeName !== 'A') el = el.parentElement;
    if (el && el.getAttribute('target') === '_blank') {
      ev.stopPropagation();
      ev.preventDefault();
    }
  }, true);
};

S['refresh-defuser'] = function (delay) {
  var wanted = delay === undefined || delay === '' ? -1 : parseInt(delay, 10);
  var defuse = function () {
    var metas = document.querySelectorAll('meta[http-equiv="refresh" i]');
    for (var i = 0; i < metas.length; i++) {
      var content = metas[i].getAttribute('content') || '';
      var seconds = parseInt(content, 10);
      if (wanted === -1 || seconds === wanted) metas[i].remove();
    }
  };
  H.onDomReady(defuse);
};

S['nowebrtc'] = function () {
  var names = ['RTCPeerConnection', 'webkitRTCPeerConnection', 'mozRTCPeerConnection'];
  var Stub = function () {
    this.close = H.noopFunc;
    this.createDataChannel = function () { return { close: H.noopFunc }; };
    this.createOffer = function () { return H.natives.Promise.reject(new DOMException('', 'NotAllowedError')); };
    this.setLocalDescription = this.createOffer;
    this.setRemoteDescription = this.createOffer;
    this.addEventListener = H.noopFunc;
    this.removeEventListener = H.noopFunc;
  };
  for (var i = 0; i < names.length; i++) {
    if (typeof window[names[i]] !== 'function') continue;
    try { window[names[i]] = Stub; } catch (e) { /* ignore */ }
  }
};

/** BlockAdBlock / FuckAdBlock stand-in: always reports "no adblock". */
function adblockDetectorStub() {
  var Detector = function () {};
  Detector.prototype = {
    check: function () { this._fire(false); return true; },
    emitEvent: function () { this._fire(false); return this; },
    clearEvent: H.noopFunc,
    setOption: function () { return this; },
    onDetected: function () { return this; },
    onNotDetected: function (fn) { if (typeof fn === 'function') H.natives.setTimeout(fn, 1); return this; },
    on: function (detected, fn) { if (!detected && typeof fn === 'function') H.natives.setTimeout(fn, 1); return this; },
    _fire: H.noopFunc
  };
  return Detector;
}

S['bab-defuser'] = function () {
  var Detector = adblockDetectorStub();
  try {
    window.BlockAdBlock = Detector;
    window.blockAdBlock = new Detector();
  } catch (e) { /* ignore */ }
};

S['fuckadblock-defuser'] = function () {
  var Detector = adblockDetectorStub();
  var instance = new Detector();
  var names = ['FuckAdBlock', 'BlockAdBlock', 'SniffAdBlock'];
  for (var i = 0; i < names.length; i++) {
    try {
      H.define(window, names[i], { configurable: true, value: Detector });
      H.define(window, names[i].charAt(0).toLowerCase() + names[i].slice(1), { configurable: true, value: instance });
    } catch (e) { /* ignore */ }
  }
};

S['popads-dummy'] = function () {
  var stub = { show: H.noopFunc, hide: H.noopFunc };
  try {
    H.define(window, 'PopAds', { configurable: true, get: function () { return stub; }, set: H.noopFunc });
    H.define(window, 'popns', { configurable: true, get: function () { return stub; }, set: H.noopFunc });
  } catch (e) { /* ignore */ }
};
