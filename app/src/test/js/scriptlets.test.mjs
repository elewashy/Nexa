/*
 * One test per scriptlet registered by assets/adblock/scriptlets/*.js.
 * The last test fails when a scriptlet is added without a test here.
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { navigate, setupPage, tick, xhr } from './harness.mjs';

const cases = {};
const scriptlet = (name, fn) => { cases[name] = fn; };

// ── Properties ───────────────────────────────────────────────────────────

scriptlet('set-constant', () => {
  const p = setupPage();
  p.run('set-constant', 'ads.config.enabled', 'false');
  p.run('set-constant', 'adsAllowed', 'trueFunc');
  p.run('set-constant', 'big', '99999');
  p.window.eval('window.ads = { config: { enabled: true, other: 1 } };');
  assert.equal(p.window.ads.config.enabled, false);
  assert.equal(p.window.ads.config.other, 1);
  assert.equal(p.window.adsAllowed(), true);
  assert.equal(p.window.big, undefined, 'untrusted numbers are capped');
  p.window.eval('window.ads.config.enabled = true;');
  assert.equal(p.window.ads.config.enabled, false, 'writes are ignored');
});

scriptlet('trusted-set-constant', () => {
  const p = setupPage();
  p.run('trusted-set-constant', 'cfg.slots', '{"count":0}');
  p.run('trusted-set-constant', 'limit', '99999');
  p.window.eval('window.cfg = {};');
  assert.deepEqual(JSON.parse(JSON.stringify(p.window.cfg.slots)), { count: 0 });
  assert.equal(p.window.limit, 99999);
});

scriptlet('abort-on-property-read', () => {
  const p = setupPage();
  p.run('abort-on-property-read', 'detector.check');
  p.window.eval('window.detector = { check: 1 };');
  assert.throws(() => p.window.eval('detector.check'), /ReferenceError|magic|[a-z0-9]{6}/);
});

scriptlet('abort-on-property-write', () => {
  const p = setupPage();
  p.run('abort-on-property-write', 'adblockFlag');
  assert.throws(() => p.window.eval('window.adblockFlag = 1;'));
});

scriptlet('abort-current-script', () => {
  const p = setupPage();
  p.run('abort-current-script', 'secret', 'adblock');
  p.window.secret = 42;
  const script = p.document.createElement('script');
  script.textContent = 'adblock(); secret;';
  // Simulate the trap firing while that script is the current script.
  Object.defineProperty(p.document, 'currentScript', { configurable: true, get: () => script });
  assert.throws(() => p.window.eval('window.secret'));
  Object.defineProperty(p.document, 'currentScript', { configurable: true, get: () => null });
  assert.equal(p.window.eval('window.secret'), 42);
});

scriptlet('abort-on-stack-trace', () => {
  const p = setupPage();
  p.run('abort-on-stack-trace', 'tracked', 'adCheckFunction');
  p.window.tracked = 1;
  assert.throws(() => p.window.eval('(function adCheckFunction() { return window.tracked; })()'));
  assert.equal(p.window.eval('(function other() { return window.tracked; })()'), 1);
});

scriptlet('noeval', () => {
  const p = setupPage();
  p.run('noeval');
  assert.equal(p.window.eval('1 + 1'), undefined);
});

scriptlet('noeval-if', () => {
  const p = setupPage();
  p.run('noeval-if', 'adblock');
  assert.equal(p.window.eval('"adblock detected"'), undefined);
  assert.equal(p.window.eval('2 * 3'), 6);
});

scriptlet('trusted-replace-argument', () => {
  const p = setupPage();
  p.window.api = { send: (a, b) => `${a}:${b}` };
  p.run('trusted-replace-argument', 'api.send', '1', '"clean"', 'condition', 'track');
  assert.equal(p.window.api.send('x', 'tracking'), 'x:clean');
  assert.equal(p.window.api.send('x', 'other'), 'x:other');
});

// ── Timers & events ──────────────────────────────────────────────────────

scriptlet('no-setTimeout-if', async () => {
  const p = setupPage();
  p.run('no-setTimeout-if', 'showAds', '10');
  const calls = [];
  p.window.cb = (v) => calls.push(v);
  p.window.eval('setTimeout(function showAds() { cb("ad"); }, 10); setTimeout(function () { cb("ok"); }, 10); setTimeout(function showAds() { cb("other-delay"); }, 20);');
  await tick(50);
  assert.deepEqual(calls.sort(), ['ok', 'other-delay']);
});

scriptlet('no-setInterval-if', async () => {
  const p = setupPage();
  p.run('no-setInterval-if', '/detect/');
  const calls = [];
  p.window.cb = (v) => calls.push(v);
  p.window.eval('var a = setInterval(function detect() { cb("bad"); }, 5); var b = setInterval(function () { cb("ok"); }, 5); setTimeout(function () { clearInterval(a); clearInterval(b); }, 30);');
  await tick(60);
  assert.ok(calls.includes('ok'));
  assert.ok(!calls.includes('bad'));
});

scriptlet('no-requestAnimationFrame-if', async () => {
  const p = setupPage();
  p.run('no-requestAnimationFrame-if', 'adFrame');
  const calls = [];
  p.window.cb = (v) => calls.push(v);
  p.window.eval('requestAnimationFrame(function adFrame() { cb("bad"); }); requestAnimationFrame(function () { cb("ok"); });');
  await tick(60);
  assert.deepEqual(calls, ['ok']);
});

scriptlet('adjust-setTimeout', () => {
  const delays = [];
  const p = setupPage({
    setup: (w) => {
      const real = w.setTimeout;
      w.setTimeout = function (fn, delay, ...rest) { delays.push(delay); return real.call(w, fn, delay, ...rest); };
    },
  });
  p.run('adjust-setTimeout', 'countdown', '10000', '0.02');
  p.window.eval('setTimeout(function countdown() {}, 10000); setTimeout(function other() {}, 10000);');
  assert.deepEqual(delays.slice(-2), [200, 10000]);
});

scriptlet('adjust-setInterval', () => {
  const delays = [];
  const p = setupPage({
    setup: (w) => {
      const real = w.setInterval;
      w.setInterval = function (fn, delay, ...rest) { delays.push(delay); const id = real.call(w, fn, delay, ...rest); w.clearInterval(id); return id; };
    },
  });
  p.run('adjust-setInterval', 'timer', '*', '0.5');
  p.window.eval('setInterval(function timer() {}, 1000);');
  assert.equal(delays.at(-1), 500);
});

scriptlet('addEventListener-defuser', () => {
  const p = setupPage();
  p.run('addEventListener-defuser', 'click', 'popunder');
  const calls = [];
  p.window.cb = (v) => calls.push(v);
  p.window.eval('document.addEventListener("click", function () { cb("popunder"); }); document.addEventListener("click", function () { cb("ok"); });');
  p.document.dispatchEvent(new p.window.Event('click'));
  assert.deepEqual(calls, ['ok']);
});

scriptlet('no-window-open-if', () => {
  const opened = [];
  const p = setupPage({ setup: (w) => { w.open = (url) => { opened.push(url); return {}; }; } });
  p.run('no-window-open-if', 'ads.example');
  assert.equal(p.window.open('https://ads.example/pop'), null);
  p.window.open('https://ok.example/');
  assert.deepEqual(opened, ['https://ok.example/']);
});

scriptlet('disable-newtab-links', () => {
  const p = setupPage({ html: '<!DOCTYPE html><body><a id="a" href="https://x.example/" target="_blank">x</a></body>' });
  p.run('disable-newtab-links');
  const ev = new p.window.MouseEvent('click', { bubbles: true, cancelable: true });
  p.document.getElementById('a').dispatchEvent(ev);
  assert.equal(ev.defaultPrevented, true);
});

scriptlet('refresh-defuser', async () => {
  const p = setupPage({ html: '<!DOCTYPE html><head><meta http-equiv="refresh" content="5;url=https://ad.example/"><meta http-equiv="refresh" content="9"></head><body></body>' });
  p.run('refresh-defuser', '5');
  await tick(10); // runs at DOMContentLoaded
  const left = [...p.document.querySelectorAll('meta[http-equiv]')].map((m) => m.getAttribute('content'));
  assert.deepEqual(left, ['9']);
});

scriptlet('nowebrtc', () => {
  const p = setupPage({ setup: (w) => { w.RTCPeerConnection = function Real() { this.real = true; }; } });
  p.run('nowebrtc');
  const pc = new p.window.RTCPeerConnection();
  assert.equal(pc.real, undefined);
  assert.equal(typeof pc.createDataChannel, 'function');
});

scriptlet('bab-defuser', async () => {
  const p = setupPage();
  p.run('bab-defuser');
  let notDetected = false;
  p.window.blockAdBlock.onNotDetected(() => { notDetected = true; });
  await tick(10);
  assert.equal(notDetected, true);
});

scriptlet('fuckadblock-defuser', async () => {
  const p = setupPage();
  p.run('fuckadblock-defuser');
  let notDetected = false;
  p.window.fuckAdBlock.onNotDetected(() => { notDetected = true; });
  await tick(10);
  assert.equal(notDetected, true);
  assert.equal(typeof p.window.FuckAdBlock, 'function');
});

scriptlet('popads-dummy', () => {
  const p = setupPage();
  p.run('popads-dummy');
  assert.equal(typeof p.window.PopAds.show, 'function');
  p.window.PopAds = 'replaced';
  assert.notEqual(p.window.PopAds, 'replaced');
});

// ── Network ──────────────────────────────────────────────────────────────

scriptlet('no-fetch-if', async () => {
  const p = setupPage({ responses: { '/ads.js': 'real ad' } });
  p.run('no-fetch-if', 'ads.js', 'emptyObj');
  const res = await p.window.fetch('https://cdn.example/ads.js');
  assert.equal(await res.text(), '{}');
  assert.equal(p.network.fetches.length, 0, 'request never reaches the network');
  await p.window.fetch('https://cdn.example/app.js');
  assert.equal(p.network.fetches.length, 1);
});

scriptlet('trusted-prevent-fetch', async () => {
  const p = setupPage();
  p.run('trusted-prevent-fetch', 'aud.springserve.com', '<VAST version="3.0"></VAST>', '{"type":"cors","status":200}');
  const res = await p.window.fetch('https://aud.springserve.com/vast');
  assert.equal(await res.text(), '<VAST version="3.0"></VAST>');
  assert.equal(res.type, 'cors');
});

scriptlet('no-xhr-if', async () => {
  const p = setupPage({ responses: { '/ads': 'real' } });
  p.run('no-xhr-if', 'method:POST /ads');
  const req = await xhr(p.window, 'https://x.example/ads', { method: 'POST' });
  assert.equal(req.responseText, '');
  assert.equal(p.network.xhrs.length, 0);
  const get = await xhr(p.window, 'https://x.example/ads');
  assert.equal(get.responseText, 'real');
});

scriptlet('trusted-prevent-xhr', async () => {
  const p = setupPage();
  p.run('trusted-prevent-xhr', 'outbrain.com', 'outbrain');
  const req = await xhr(p.window, 'https://widgets.outbrain.com/x.js');
  assert.equal(req.responseText, 'outbrain');
  assert.equal(req.status, 200);
});

scriptlet('json-prune', async () => {
  const p = setupPage();
  p.run('json-prune', 'playerResponse.adPlacements entries.[-].ad.isAd');
  const obj = p.window.JSON.parse('{"playerResponse":{"adPlacements":[1],"video":2},"entries":[{"ad":{"isAd":true}},{"id":3}]}');
  assert.deepEqual(JSON.parse(JSON.stringify(obj)), { playerResponse: { video: 2 }, entries: [{ id: 3 }] });
  const res = new p.window.Response('{"playerResponse":{"adPlacements":[1]}}');
  assert.deepEqual(JSON.parse(JSON.stringify(await res.json())), { playerResponse: {} });
});

scriptlet('json-prune-fetch-response', async () => {
  // Bodies the filter does not change are passed through untouched (formatting included).
  const untouched = setupPage({ responses: { '/player': '{ "video": 1 }' } });
  untouched.run('json-prune-fetch-response', 'adSlots', '', 'propsToMatch', '/player');
  assert.equal(await (await untouched.window.fetch('https://yt.example/player')).text(), '{ "video": 1 }');

  const p = setupPage({ responses: { '/player': '{"adSlots":[1],"video":{"id":1}}', '/other': '{"adSlots":[1]}' } });
  p.run('json-prune-fetch-response', 'adSlots', '', 'propsToMatch', '/player');
  assert.deepEqual(await (await p.window.fetch('https://yt.example/player?x')).json(), { video: { id: 1 } });
  assert.deepEqual(await (await p.window.fetch('https://yt.example/other')).json(), { adSlots: [1] });
});

scriptlet('json-prune-xhr-response', async () => {
  const p = setupPage({ responses: { '/player': '{"adPlacements":[1],"ok":true}' } });
  p.run('json-prune-xhr-response', 'adPlacements', '', 'propsToMatch', '/player');
  const text = await xhr(p.window, 'https://yt.example/player');
  assert.equal(text.responseText, '{"ok":true}');
  const json = await xhr(p.window, 'https://yt.example/player', { responseType: 'json' });
  assert.deepEqual(JSON.parse(JSON.stringify(json.response)), { ok: true });
});

scriptlet('trusted-replace-fetch-response', async () => {
  const p = setupPage({ responses: { 'player?': '{"adSlots":[],"adSlots2":1}' } });
  p.run('trusted-replace-fetch-response', '"adSlots"', '"no_ads"', 'player?');
  assert.equal(await (await p.window.fetch('https://yt.example/youtubei/v1/player?key=1')).text(), '{"no_ads":[],"adSlots2":1}');
});

scriptlet('trusted-replace-xhr-response', async () => {
  const p = setupPage({ responses: { '/player': '{"adPlacements":[{"x":1}],"adSlots":[],"video":1}' } });
  p.run('trusted-replace-xhr-response', '/"adPlacements.*?("adSlots")/', '$1', '/player');
  let seenByEarlyListener;
  const req = new p.window.XMLHttpRequest();
  // A listener registered before send() still sees the rewritten body.
  req.addEventListener('readystatechange', () => { if (req.readyState === 4) seenByEarlyListener = req.responseText; });
  req.open('GET', 'https://yt.example/player');
  req.send();
  await tick(20);
  assert.equal(seenByEarlyListener, '{"adSlots":[],"video":1}');
});

scriptlet('xml-prune', async () => {
  const vast = '<VAST version="3.0"><Ad id="1"><InLine/></Ad><Ad id="2"><InLine/></Ad></VAST>';
  const p = setupPage({ responses: { '/tserver': vast } });
  p.run('xml-prune', 'VAST > Ad', '', '/tserver');
  const text = await (await p.window.fetch('https://x.example/tserver?v=1')).text();
  assert.ok(!text.includes('<Ad'), text);
  const req = await xhr(p.window, 'https://x.example/tserver');
  assert.equal(req.responseXML.querySelectorAll('Ad').length, 0);
  // xpath() selectors. jsdom's XPath engine supports neither name() nor the
  // attribute axis (Chrome supports both; checked on device), so only an
  // element expression is exercised here.
  const mpd = '<MPD><Period id="ad-1"/><Period id="main"/></MPD>';
  const q = setupPage({ responses: { '.mpd': mpd } });
  q.run('xml-prune', 'xpath(//Period[starts-with(@id,"ad")])', '[id="main"]', '.mpd');
  const out = await (await q.window.fetch('https://x.example/v.mpd')).text();
  assert.ok(!out.includes('ad-1') && out.includes('main'), out);
});

scriptlet('mpegdash-prune', async () => {
  const mpd = '<MPD><Period id="p1"><EventStream schemeIdUri="urn:sva:advertising-wg:ad-id-signaling"/></Period><Period id="p2"/></MPD>';
  const p = setupPage({ responses: { '/dash': mpd, '/vast': '<VAST><Period/></VAST>' } });
  p.run('mpegdash-prune', 'Period:has(> EventStream[schemeIdUri="urn:sva:advertising-wg:ad-id-signaling"])', '/dash');
  const out = await (await p.window.fetch('https://x.example/dash/m.mpd')).text();
  assert.ok(!out.includes('p1') && out.includes('p2'), out);
});

scriptlet('m3u-prune', async () => {
  const m3u = '#EXTM3U\n#EXTINF:5,\nhttps://ads.example/dclk_video_ads/1.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:5,\nhttps://cdn.example/seg1.ts\n';
  const p = setupPage({ responses: { '.m3u8': m3u } });
  p.run('m3u-prune', 'dclk_video_ads', '.m3u8');
  const out = await (await p.window.fetch('https://cdn.example/index.m3u8')).text();
  assert.equal(out, '#EXTM3U\n#EXTINF:5,\nhttps://cdn.example/seg1.ts\n');
});

// ── json-edit family ─────────────────────────────────────────────────────

scriptlet('json-edit', () => {
  const p = setupPage();
  p.run('json-edit', '..nodes.*[?.sponsored_data]');
  const out = p.window.JSON.parse('{"a":{"nodes":[{"id":1,"sponsored_data":{}},{"id":2}]}}');
  assert.deepEqual(JSON.parse(JSON.stringify(out)), { a: { nodes: [{ id: 2 }] } });
  const q = setupPage();
  q.run('json-edit', '.showAds=false');
  assert.deepEqual(JSON.parse(JSON.stringify(q.window.JSON.parse('{"showAds":true}'))), { showAds: true }, 'assignments need a trusted filter');
});

scriptlet('trusted-json-edit', () => {
  const p = setupPage();
  p.run('trusted-json-edit', '..showAds=false');
  p.run('trusted-json-edit', '.features.*[?.slug=="adblock-detection"].enabled=false');
  const out = p.window.JSON.parse('{"x":{"showAds":true},"features":[{"slug":"adblock-detection","enabled":true},{"slug":"y","enabled":true}]}');
  assert.deepEqual(JSON.parse(JSON.stringify(out)), {
    x: { showAds: false },
    features: [{ slug: 'adblock-detection', enabled: false }, { slug: 'y', enabled: true }],
  });
});

scriptlet('json-edit-fetch-response', async () => {
  const p = setupPage({ responses: { '/search': '{"items":[{"isAd":true},{"id":1}]}' } });
  p.run('json-edit-fetch-response', '.items.*[?.isAd==true]', 'propsToMatch', '/search');
  assert.deepEqual(await (await p.window.fetch('https://x.example/search?q')).json(), { items: [{ id: 1 }] });
});

scriptlet('trusted-json-edit-fetch-response', async () => {
  const p = setupPage({ responses: { '/embed/settings': '{"ads_mode":"1"}' } });
  p.run('trusted-json-edit-fetch-response', '$+={"ads_suppressed":true}', 'propsToMatch', '/embed/settings');
  p.run('trusted-json-edit-fetch-response', '.ads_mode="0"', 'propsToMatch', '/embed/settings');
  assert.deepEqual(await (await p.window.fetch('https://x.example/embed/settings')).json(), { ads_mode: '0', ads_suppressed: true });
});

scriptlet('json-edit-xhr-response', async () => {
  const p = setupPage({ responses: { '/livestitch': '{"result":{"timeline":[{"type":"ad"},{"type":"video"}]}}' } });
  p.run('json-edit-xhr-response', '.result.timeline.*[?.type=="ad"]', 'propsToMatch', '/livestitch');
  const req = await xhr(p.window, 'https://x.example/livestitch', { responseType: 'json' });
  assert.deepEqual(JSON.parse(JSON.stringify(req.response)), { result: { timeline: [{ type: 'video' }] } });
});

scriptlet('trusted-json-edit-xhr-response', async () => {
  const p = setupPage({ responses: { '/playback': '{"a":{"ads_enabled":"1"}}' } });
  p.run('trusted-json-edit-xhr-response', '..ads_enabled="0"', 'propsToMatch', '/playback');
  assert.equal((await xhr(p.window, 'https://x.example/playback')).responseText, '{"a":{"ads_enabled":"0"}}');
});

scriptlet('json-edit-fetch-request', async () => {
  const p = setupPage();
  p.run('json-edit-fetch-request', '.events.*[?.operationName=="TrackEvent"]', 'propsToMatch', '/v1/api');
  await p.window.fetch('https://x.example/v1/api', { method: 'POST', body: '{"events":[{"operationName":"TrackEvent"},{"operationName":"Load"}]}' });
  assert.equal(p.network.fetches[0].init.body, '{"events":[{"operationName":"Load"}]}');
});

scriptlet('trusted-json-edit-fetch-request', async () => {
  const p = setupPage();
  p.run('trusted-json-edit-fetch-request', '.context.client+={"clientScreen":"ADUNIT"}', 'propsToMatch', '/get_watch');
  await p.window.fetch('https://yt.example/get_watch?x', { method: 'POST', body: '{"context":{"client":{"name":"WEB"}}}' });
  assert.deepEqual(JSON.parse(p.network.fetches[0].init.body), { context: { client: { name: 'WEB', clientScreen: 'ADUNIT' } } });
});

scriptlet('json-edit-xhr-request', async () => {
  const p = setupPage();
  p.run('json-edit-xhr-request', '.tracking', 'propsToMatch', '/collect');
  await xhr(p.window, 'https://x.example/collect', { method: 'POST', body: '{"tracking":{"id":1},"data":2}' });
  assert.equal(p.network.xhrs[0].body, '{"data":2}');
});

scriptlet('trusted-json-edit-xhr-request', async () => {
  const p = setupPage();
  p.run('trusted-json-edit-xhr-request', '.consent=true', 'propsToMatch', '/collect');
  await xhr(p.window, 'https://x.example/collect', { method: 'POST', body: '{"consent":false}' });
  assert.equal(p.network.xhrs[0].body, '{"consent":true}');
});

scriptlet('jsonl-edit-fetch-response', async () => {
  const p = setupPage({ responses: { '/graphql': '{"node":{"__typename":"AdStory"}}\n{"node":{"__typename":"Story"}}' } });
  p.run('jsonl-edit-fetch-response', '..node[?.__typename=="AdStory"]', 'propsToMatch', '/graphql');
  assert.equal(await (await p.window.fetch('https://x.example/graphql')).text(), '{}\n{"node":{"__typename":"Story"}}');
});

scriptlet('trusted-jsonl-edit-fetch-response', async () => {
  const p = setupPage({ responses: { '/feed': '{"ad":true}\nnot json\n{"ad":true}' } });
  p.run('trusted-jsonl-edit-fetch-response', '.ad=false', 'propsToMatch', '/feed');
  assert.equal(await (await p.window.fetch('https://x.example/feed')).text(), '{"ad":false}\nnot json\n{"ad":false}');
});

scriptlet('jsonl-edit-xhr-response', async () => {
  const p = setupPage({ responses: { '/graphql': '{"data":{"category":"SPONSORED","node":{"id":1}}}\n{"data":{"category":"ORGANIC","node":{"id":2}}}' } });
  p.run('jsonl-edit-xhr-response', '.data[?.category=="SPONSORED"].node', 'propsToMatch', '/graphql');
  const req = await xhr(p.window, 'https://x.example/graphql');
  assert.equal(req.responseText, '{"data":{"category":"SPONSORED"}}\n{"data":{"category":"ORGANIC","node":{"id":2}}}');
});

scriptlet('trusted-jsonl-edit-xhr-response', async () => {
  const p = setupPage({ responses: { '/graphql': '{"a":1}\n{"a":2}' } });
  p.run('trusted-jsonl-edit-xhr-response', '.a=0', 'propsToMatch', '/graphql');
  assert.equal((await xhr(p.window, 'https://x.example/graphql')).responseText, '{"a":0}\n{"a":0}');
});

scriptlet('edit-inbound-object', () => {
  const p = setupPage();
  p.run('edit-inbound-object', 'Object.keys', '0', '[?.context.bidRequestId].*');
  const input = { context: { bidRequestId: 1 }, other: 2 };
  assert.deepEqual(JSON.parse(JSON.stringify(p.window.Object.keys(input))), []);
  assert.deepEqual(Object.keys(input), ['context', 'other'], 'the caller\'s object is not mutated');
});

scriptlet('trusted-edit-inbound-object', () => {
  const p = setupPage();
  p.run('trusted-edit-inbound-object', 'JSON.stringify', '0',
    '[?.attestationRequest][?.context.client.userAgent*="channel"].context.client[?.clientName=="WEB"]+={"clientScreen":"CHANNEL"}');
  p.run('trusted-edit-inbound-object', 'JSON.stringify', '0',
    '[?.attestationRequest][?.context.client.userAgent=/lactmilli|channel/].playbackContext.contentPlaybackContext.referer=repl({"regex":"(?:#reloadxhr)?$","replacement":"#reloadxhr"})');
  const body = {
    attestationRequest: {},
    context: { client: { clientName: 'WEB', userAgent: 'Mozilla channel' } },
    playbackContext: { contentPlaybackContext: { referer: 'https://www.youtube.com/watch' } },
  };
  const out = JSON.parse(p.window.JSON.stringify(body));
  assert.equal(out.context.client.clientScreen, 'CHANNEL');
  assert.equal(out.playbackContext.contentPlaybackContext.referer, 'https://www.youtube.com/watch#reloadxhr');
  assert.equal(JSON.parse(p.window.JSON.stringify({ context: { client: { clientName: 'WEB' } } })).context.client.clientScreen, undefined);
  // Objects the query does not select are not deep-copied (JSON.stringify is hot).
  let reads = 0;
  const unrelated = { get heavy() { reads++; return 1; } };
  p.window.JSON.stringify(unrelated);
  assert.equal(reads, 1, 'only the real stringify reads the object');
});

scriptlet('edit-outbound-object', () => {
  const p = setupPage();
  p.window.api = { config: () => ({ ads: [1], ok: true }) };
  p.run('edit-outbound-object', 'api.config', '.ads');
  assert.deepEqual(JSON.parse(JSON.stringify(p.window.api.config())), { ok: true });
});

scriptlet('trusted-edit-outbound-object', () => {
  const p = setupPage();
  p.window.api = { config: () => ({ adsEnabled: true }) };
  p.run('trusted-edit-outbound-object', 'api.config', '.adsEnabled=false');
  assert.equal(p.window.api.config().adsEnabled, false);
});

scriptlet('edit-object-on-setter', () => {
  const p = setupPage();
  p.run('edit-object-on-setter', 'flashvars', 'v2:$./^adv_/');
  p.window.eval('window.flashvars = { adv_pre: 1, adv_post: 2, video: 3 };');
  assert.deepEqual(JSON.parse(JSON.stringify(p.window.flashvars)), { video: 3 });
});

scriptlet('trusted-edit-object-on-setter', () => {
  const p = setupPage();
  p.run('trusted-edit-object-on-setter', 'player.config', '.preroll=false');
  p.window.eval('window.player = {}; player.config = { preroll: true };');
  assert.equal(p.window.player.config.preroll, false);
});

// ── DOM ──────────────────────────────────────────────────────────────────

scriptlet('remove-node-text', async () => {
  const p = setupPage();
  p.run('remove-node-text', 'script', 'adblock');
  const s = p.document.createElement('script');
  s.textContent = 'detect adblock';
  p.document.body.appendChild(s);
  await tick(10);
  assert.equal(s.textContent, '');
});

scriptlet('replace-node-text', async () => {
  const p = setupPage();
  p.run('replace-node-text', 'script', '/showAds\\s*=\\s*true/', 'showAds=false', 'condition', 'config');
  const s = p.document.createElement('script');
  s.textContent = 'var config; showAds = true;';
  p.document.body.appendChild(s);
  await tick(10);
  assert.equal(s.textContent, 'var config; showAds=false;');
});

scriptlet('remove-attr', () => {
  const p = setupPage({ html: '<!DOCTYPE html><body><a id="a" href="#" onclick="pop()" data-x="1">x</a></body>' });
  p.run('remove-attr', 'onclick|data-x', '#a', 'asap');
  const a = p.document.getElementById('a');
  assert.equal(a.hasAttribute('onclick'), false);
  assert.equal(a.hasAttribute('data-x'), false);
});

scriptlet('remove-class', () => {
  const p = setupPage({ html: '<!DOCTYPE html><body class="no-scroll adblock-on keep"></body>' });
  p.run('remove-class', 'no-scroll|adblock-on', 'body', 'asap');
  assert.equal(p.document.body.className, 'keep');
});

scriptlet('set-attr', async () => {
  const p = setupPage({ html: '<!DOCTYPE html><body><video id="v" data-src="a.mp4"></video></body>' });
  p.run('set-attr', '#v', 'src', '[data-src]');
  p.run('set-attr', '#v', 'onplay', 'true');
  p.run('set-attr', '#v', 'title', 'arbitrary text');
  await tick(10);
  const v = p.document.getElementById('v');
  assert.equal(v.getAttribute('src'), 'a.mp4');
  assert.equal(v.hasAttribute('onplay'), false, 'event handler attributes are never set');
  assert.equal(v.hasAttribute('title'), false, 'untrusted values are limited');
});

scriptlet('trusted-set-attr', async () => {
  const p = setupPage({ html: '<!DOCTYPE html><body><div id="d"></div></body>' });
  p.run('trusted-set-attr', '#d', 'title', 'arbitrary text');
  await tick(10);
  assert.equal(p.document.getElementById('d').getAttribute('title'), 'arbitrary text');
});

scriptlet('set-cookie', () => {
  const p = setupPage();
  p.run('set-cookie', 'consent', 'accepted');
  p.run('set-cookie', 'evil', '<script>');
  assert.match(p.document.cookie, /consent=accepted/);
  assert.doesNotMatch(p.document.cookie, /evil/);
});

scriptlet('trusted-set-cookie', () => {
  const p = setupPage();
  p.run('trusted-set-cookie', 'visit', 'ts-$now$', '3600');
  assert.match(p.document.cookie, /visit=ts-\d{13}/);
});

scriptlet('trusted-set-cookie-reload', () => {
  const p = setupPage();
  p.run('trusted-set-cookie-reload', 'godbayadblock', 'godbayadblock');
  assert.match(p.document.cookie, /godbayadblock=godbayadblock/);
  assert.ok(p.errors.some((e) => /navigation|reload/i.test(e)), 'reloads after changing the cookie');
  const reloads = p.errors.length;
  p.run('trusted-set-cookie-reload', 'godbayadblock', 'godbayadblock');
  assert.equal(p.errors.length, reloads, 'no reload when the cookie already has the value');
});

scriptlet('remove-cookie', () => {
  const p = setupPage();
  p.document.cookie = 'ad_session=1; path=/';
  p.document.cookie = 'keep=1; path=/';
  p.run('remove-cookie', '/^ad_/');
  assert.doesNotMatch(p.document.cookie, /ad_session/);
  assert.match(p.document.cookie, /keep=1/);
});

scriptlet('set-local-storage-item', () => {
  const p = setupPage();
  p.run('set-local-storage-item', 'adblockDismissed', 'true');
  p.run('set-local-storage-item', 'payload', '{"a":1}');
  assert.equal(p.window.localStorage.getItem('adblockDismissed'), 'true');
  assert.equal(p.window.localStorage.getItem('payload'), null);
});

scriptlet('set-session-storage-item', () => {
  const p = setupPage();
  p.window.sessionStorage.setItem('ad_1', 'x');
  p.window.sessionStorage.setItem('ad_2', 'y');
  p.run('set-session-storage-item', '/^ad_/', '$remove$');
  assert.equal(p.window.sessionStorage.length, 0);
});

scriptlet('trusted-set-local-storage-item', () => {
  const p = setupPage();
  p.run('trusted-set-local-storage-item', 'config', '{"ads":false}');
  assert.equal(p.window.localStorage.getItem('config'), '{"ads":false}');
});

scriptlet('trusted-set-session-storage-item', () => {
  const p = setupPage();
  p.run('trusted-set-session-storage-item', 'seen', '$now$');
  assert.match(p.window.sessionStorage.getItem('seen'), /^\d{13}$/);
});

scriptlet('href-sanitizer', async () => {
  const p = setupPage({ html: '<!DOCTYPE html><body><a id="a" href="https://track.example/?url=https%3A%2F%2Freal.example%2Fpage">x</a></body>' });
  p.run('href-sanitizer', '#a', '?url');
  await tick(10);
  assert.equal(p.document.getElementById('a').getAttribute('href'), 'https://real.example/page');
});

scriptlet('trusted-click-element', async () => {
  const p = setupPage({ html: '<!DOCTYPE html><body></body>' });
  const clicks = [];
  p.run('trusted-click-element', '#accept, #close', '', '0');
  await tick(5);
  const accept = p.document.createElement('button');
  accept.id = 'accept';
  accept.addEventListener('click', () => clicks.push('accept'));
  const close = p.document.createElement('button');
  close.id = 'close';
  close.addEventListener('click', () => clicks.push('close'));
  p.document.body.append(accept, close);
  await tick(20);
  assert.deepEqual(clicks, ['accept', 'close']);
});

// ── Native method overrides ──────────────────────────────────────────────

scriptlet('trusted-suppress-native-method', () => {
  const p = setupPage();
  const created = [];
  p.window.api = { load: (kind, src) => { created.push(`${kind}:${src}`); return 'loaded'; } };
  p.run('trusted-suppress-native-method', 'api.load', '"script"|/ads\\.js/');
  assert.equal(p.window.api.load('script', 'https://x/ads.js'), undefined);
  assert.equal(p.window.api.load('script', 'https://x/app.js'), 'loaded');
  assert.equal(p.window.api.load('style', 'https://x/ads.js'), 'loaded');
  p.run('trusted-suppress-native-method', 'api.load', '{"kind":"beacon"}', 'abort');
  assert.throws(() => p.window.api.load({ kind: 'beacon' }));
});

scriptlet('trusted-override-element-method', () => {
  const p = setupPage({ html: '<!DOCTYPE html><body><a id="pop" target="_blank" href="#">x</a><a id="ok" href="#">y</a></body>' });
  const clicked = [];
  p.document.addEventListener('click', (e) => clicked.push(e.target.id));
  p.run('trusted-override-element-method', 'HTMLAnchorElement.prototype.click', 'a[target="_blank"]');
  p.document.getElementById('pop').click();
  p.document.getElementById('ok').click();
  assert.deepEqual(clicked, ['ok']);
});

scriptlet('trusted-replace-outbound-text', () => {
  const p = setupPage();
  p.run('trusted-replace-outbound-text', 'JSON.stringify', '"clientScreen":"WATCH"', '"clientScreen":"ADUNIT"', 'condition', 'contentPlaybackContext');
  assert.equal(p.window.JSON.stringify({ clientScreen: 'WATCH', contentPlaybackContext: 1 }), '{"clientScreen":"ADUNIT","contentPlaybackContext":1}');
  assert.equal(p.window.JSON.stringify({ clientScreen: 'WATCH' }), '{"clientScreen":"WATCH"}');
  const q = setupPage();
  q.run('trusted-replace-outbound-text', 'atob', '/^https:\\/\\/ads\\..+/', 'about:blank');
  assert.equal(q.window.atob(Buffer.from('https://ads.example/x').toString('base64')), 'about:blank');
});

scriptlet('trusted-prevent-dom-bypass', () => {
  const p = setupPage();
  const patchedFetch = () => 'patched';
  p.window.fetch = patchedFetch;
  p.run('trusted-prevent-dom-bypass', 'Node.prototype.appendChild', 'fetch');
  const frame = p.document.createElement('iframe');
  p.document.body.appendChild(frame);
  assert.equal(frame.contentWindow.fetch, patchedFetch);
});

// ── CSS / DOM helpers ────────────────────────────────────────────────────

scriptlet('spoof-css', () => {
  const p = setupPage({ html: '<!DOCTYPE html><body><div class="ad" style="visibility:hidden;height:0"></div><div class="x" style="visibility:hidden"></div></body>' });
  p.run('spoof-css', '.ad', 'visibility', 'visible', 'height', '250px');
  const ad = p.document.querySelector('.ad');
  const style = p.window.getComputedStyle(ad);
  assert.equal(style.visibility, 'visible');
  assert.equal(style.getPropertyValue('visibility'), 'visible');
  assert.equal(ad.getBoundingClientRect().height, 250);
  assert.equal(p.window.getComputedStyle(p.document.querySelector('.x')).visibility, 'hidden');
});

scriptlet('prevent-innerHTML', () => {
  const p = setupPage({ html: '<!DOCTYPE html><body><div id="a"></div><div id="b"></div></body>' });
  p.run('prevent-innerHTML', '#a', 'adblock');
  const a = p.document.getElementById('a');
  a.innerHTML = '<p>Please disable your adblock</p>';
  assert.equal(a.innerHTML, '');
  a.innerHTML = '<p>fine</p>';
  assert.equal(a.innerHTML, '<p>fine</p>');
  p.document.getElementById('b').innerHTML = 'adblock';
  assert.equal(p.document.getElementById('b').innerHTML, 'adblock');
});

scriptlet('trusted-create-html', async () => {
  const p = setupPage();
  p.run('trusted-create-html', '#late', '<div id="bait" class="adsbygoogle"></div><span id="tmp"></span>', '50');
  const host = p.document.createElement('section');
  host.id = 'late';
  p.document.body.appendChild(host);
  await tick(10);
  assert.ok(p.document.querySelector('#late > #bait'));
  await tick(80);
  assert.equal(p.document.getElementById('bait'), null, 'removed after the duration');
});

// ── Navigation / UI ──────────────────────────────────────────────────────

scriptlet('prevent-navigation', () => {
  const p = setupPage();
  p.run('prevent-navigation', '/werbeblocker/i');
  assert.equal(navigate(p.window, 'https://site.com/Werbeblocker-erkannt'), true);
  assert.equal(navigate(p.window, 'https://site.com/article'), false);
  assert.equal(navigate(p.window, 'https://site.com/werbeblocker', { userInitiated: true }), false);
});

scriptlet('window-close-if', () => {
  let closed = 0;
  const p = setupPage({ url: 'https://norton.com/protect?x=1', setup: (w) => { w.close = () => { closed++; }; } });
  p.run('window-close-if', '/protect?');
  p.run('window-close-if', '/nomatch');
  assert.equal(closed, 1);
});

scriptlet('alert-buster', () => {
  const alerts = [];
  const p = setupPage({ setup: (w) => { w.alert = (m) => alerts.push(m); } });
  p.run('alert-buster');
  assert.equal(p.window.alert('Disable your adblocker'), undefined);
  assert.deepEqual(alerts, []);
});

scriptlet('prevent-canvas', () => {
  const contexts = [];
  const p = setupPage({ setup: (w) => { w.HTMLCanvasElement.prototype.getContext = function (type) { contexts.push(type); return { type }; }; } });
  p.run('prevent-canvas', '2d');
  const canvas = p.document.createElement('canvas');
  assert.equal(canvas.getContext('2d'), null);
  assert.deepEqual(canvas.getContext('webgl'), { type: 'webgl' });
  assert.deepEqual(contexts, ['webgl']);
});

scriptlet('prevent-clipboard-write', async () => {
  const p = setupPage();
  p.run('prevent-clipboard-write', '/^powershell .*-w(indowStyle)? h(idden)?/i', 'domAlert', 'Blocked: ${text}', 'excludeMatches', '/^powershell -version/i');
  await p.window.navigator.clipboard.writeText('powershell -w hidden iex(irm evil)');
  await p.window.navigator.clipboard.writeText('powershell -Version');
  await p.window.navigator.clipboard.writeText('hello');
  assert.deepEqual(p.network.clipboard, ['powershell -Version', 'hello']);
  const transfer = new p.window.DataTransfer();
  transfer.setData('text/plain', 'PowerShell -WindowStyle Hidden x');
  assert.equal(transfer.getData('text/plain'), undefined);
  await tick(5);
  assert.match(p.document.body.textContent, /Blocked: powershell -w hidden/);
});

scriptlet('proxy-apply-config', () => {
  const p = setupPage();
  const nativeToString = p.window.Function.prototype.toString;
  p.run('proxy-apply-config', '{"skipToString":true}');
  p.H.installToStringCloak();
  assert.equal(p.window.Function.prototype.toString, nativeToString);
  const q = setupPage();
  q.H.installToStringCloak();
  assert.notEqual(q.window.Function.prototype.toString, nativeToString);
});

// ── Injectable resources ─────────────────────────────────────────────────

scriptlet('popads.net', () => {
  const p = setupPage();
  p.run('popads.net');
  assert.throws(() => p.window.eval('window.PopAds = {};'));
  assert.equal(p.window.popns, undefined);
});

scriptlet('fingerprint2', async () => {
  const p = setupPage();
  p.run('fingerprint2');
  const id = await new Promise((resolve) => p.window.Fingerprint2.get((value) => resolve(value)));
  assert.match(id, /^[0-9a-f]{32}$/);
});

scriptlet('multiup', () => {
  const p = setupPage({ html: '<!DOCTYPE html><body><form><button link="https://mirror.example/file">Download</button></form></body>' });
  p.run('multiup');
  const ev = new p.window.MouseEvent('click', { bubbles: true, cancelable: true });
  p.document.querySelector('button').dispatchEvent(ev);
  assert.equal(ev.defaultPrevented, true);
  assert.ok(p.errors.some((e) => /navigation/i.test(e)), 'navigates to the direct link');
});

// ── Registration ─────────────────────────────────────────────────────────

for (const [name, fn] of Object.entries(cases)) test(`scriptlet: ${name}`, fn);

test('every registered scriptlet has a test', () => {
  const { S } = setupPage();
  const untested = Object.keys(S).filter((name) => !(name in cases));
  const unknown = Object.keys(cases).filter((name) => !(name in S));
  assert.deepEqual(untested, [], 'scriptlets without a test');
  assert.deepEqual(unknown, [], 'tests for unregistered scriptlets');
});
