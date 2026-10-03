/* Content script: payload handling, `$csp` meta policies and procedural operators. */
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { createPage, loadLibrary, tick } from './harness.mjs';

const EMPTY = { css: '', procedural: [], generic: false, scriptlets: [] };

/** Loads the content script with a bridge serving [payload]. */
function withPayload(payload, { html, setup } = {}) {
  const page = createPage({ html, setup });
  const requests = [];
  const bridge = {
    cosmetics: (frame, top) => { requests.push([frame, top]); return JSON.stringify({ ...EMPTY, ...payload }); },
    genericCss: () => '',
    popup: () => {},
  };
  loadLibrary(page, { content: true, bridge });
  return { ...page, requests };
}

const hidden = (el) => el.style.getPropertyValue('display') === 'none';

describe('content script', () => {
  test('asks for its frame payload, deletes the bridge and runs listed scriptlets', () => {
    const p = withPayload({ scriptlets: [['set-constant', ['adsEnabled', 'false']], ['no-such-scriptlet', []]] });
    assert.deepEqual(p.requests, [['https://site.com/', '']]);
    assert.equal('__NEXA_BRIDGE__' in p.window, false);
    assert.equal(p.window.adsEnabled, false);
  });

  test('applies $csp policies through a transient meta element', () => {
    const inserted = [];
    const p = withPayload({ csp: ["script-src 'self'", "worker-src 'none'"] }, {
      setup: (w) => {
        new w.MutationObserver((records) => {
          for (const r of records) for (const n of r.addedNodes) {
            if (n.nodeName === 'META') inserted.push([n.getAttribute('http-equiv'), n.getAttribute('content'), n.parentNode]);
          }
        }).observe(w.document, { childList: true, subtree: true });
      },
    });
    return tick(0).then(() => {
      assert.deepEqual(inserted.map(([equiv, content]) => [equiv, content]), [
        ['Content-Security-Policy', "script-src 'self'"],
        ['Content-Security-Policy', "worker-src 'none'"],
      ]);
      assert.equal(p.document.querySelectorAll('meta').length, 0, 'nothing is left in the DOM');
      assert.equal(p.document.querySelectorAll('head').length, 1);
    });
  });

  test(':others() hides everything outside the subject, its ancestors and descendants', async () => {
    const p = withPayload({ procedural: ['#player:others()'] }, {
      html: '<!DOCTYPE html><body><header id="h">h</header><main id="m"><aside id="a">ad</aside><div id="player"><video id="v"></video></div></main><script id="s"></script></body>',
    });
    await tick(20);
    const $ = (id) => p.document.getElementById(id);
    assert.ok(hidden($('h')) && hidden($('a')));
    assert.ok(!hidden($('m')) && !hidden($('player')) && !hidden($('v')));
    assert.ok(!hidden($('s')), 'structural elements are left alone');
  });

  test(':shadow() selects inside open shadow roots', async () => {
    const p = withPayload({ procedural: ['#host:shadow(.ad)'] }, {
      html: '<!DOCTYPE html><body><div id="host"></div></body>',
      setup: (w) => {
        const root = w.document.getElementById('host').attachShadow({ mode: 'open' });
        root.innerHTML = '<div class="ad">ad</div><div class="content">ok</div>';
      },
    });
    await tick(20);
    const root = p.document.getElementById('host').shadowRoot;
    assert.ok(hidden(root.querySelector('.ad')));
    assert.ok(!hidden(root.querySelector('.content')));
  });

  test(':matches-prop() tests JavaScript properties', async () => {
    const p = withPayload({ procedural: ['div:matches-prop(dataset.sponsored)', 'section:matches-prop(adConfig.kind=/^video/)'] }, {
      html: '<!DOCTYPE html><body><div id="a" data-sponsored="1"></div><div id="b"></div><section id="c"></section><section id="d"></section></body>',
      setup: (w) => {
        w.document.getElementById('c').adConfig = { kind: 'video-preroll' };
        w.document.getElementById('d').adConfig = { kind: 'banner' };
      },
    });
    await tick(20);
    const $ = (id) => p.document.getElementById(id);
    assert.ok(hidden($('a')) && hidden($('c')));
    assert.ok(!hidden($('b')) && !hidden($('d')));
  });

  test(':watch-attr() re-runs filters when the watched attribute changes', async () => {
    const p = withPayload({ procedural: ['.slot[data-state="ad"]:watch-attr(data-state)'] }, {
      html: '<!DOCTYPE html><body><div class="slot" id="s" data-state="empty"></div></body>',
    });
    await tick(20);
    const slot = p.document.getElementById('s');
    assert.ok(!hidden(slot));
    slot.setAttribute('data-state', 'ad'); // attribute-only change: no childList/class/id mutation
    await tick(250);
    assert.ok(hidden(slot));
  });

  test('patched APIs are cloaked once the content script has run', () => {
    let openSource;
    const p = withPayload({}, { setup: (w) => { openSource = w.Function.prototype.toString.call(w.open); } });
    assert.equal(p.window.Function.prototype.toString.call(p.window.open), openSource);
  });
});
