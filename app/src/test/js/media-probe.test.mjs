/* Page media probe (assets/media/page_media_probe.js): focal-content scoping and reporting. */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, test } from 'node:test';
import { createPage, tick } from './harness.mjs';

const PROBE = readFileSync(
  path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../main/assets/media/page_media_probe.js'),
  'utf8',
);

/** Loads the probe into a page at [url]; returns the page and the decoded messages it posted. */
function probe(url, html) {
  const page = createPage({ url, html: `<!DOCTYPE html><html><head></head><body>${html || ''}</body></html>` });
  const messages = [];
  page.window.__NEXA_PROBE__ = { postMessage: (data) => messages.push(JSON.parse(data)) };
  page.window.eval(PROBE);
  return { ...page, messages };
}

const settle = () => tick(400);

const tweet = (id, media = '') =>
  `<article data-testid="tweet"><a href="/user/status/${id}"><time datetime="2026-10-02">Oct 2</time></a>` +
  `<div data-testid="tweetText">text</div>${media}</article>`;

describe('page media probe', () => {
  test('reports media in the focal tweet', async () => {
    const id = '2106095256093766131';
    const p = probe(`https://x.com/user/status/${id}`, tweet(id, '<div data-testid="videoPlayer"><video></video></div>'));
    await settle();
    assert.deepEqual(p.messages, [{ url: `https://x.com/user/status/${id}`, hasMedia: true }]);
  });

  test('ignores media of replies, quoted tweets and link cards', async () => {
    const id = '111';
    const html =
      tweet(id, '<div role="link"><div data-testid="User-Name">Quoted</div>' +
        '<div data-testid="tweetPhoto"><img src="https://pbs.twimg.com/media/Q.jpg" width="500"></div></div>' +
        '<div data-testid="card.wrapper"><img src="https://pbs.twimg.com/card_img/1/c.jpg" width="500" height="260"></div>' +
        '<img src="https://pbs.twimg.com/profile_images/1/a_normal.jpg" width="40">') +
      tweet('222', '<div data-testid="tweetPhoto"><img src="reply.jpg"></div>');
    const p = probe(`https://x.com/user/status/${id}`, html);
    await settle();
    assert.deepEqual(p.messages, [{ url: `https://x.com/user/status/${id}`, hasMedia: false }]);
  });

  test('reports again when media renders later and after SPA navigation', async () => {
    const p = probe('https://x.com/user/status/111', tweet('111'));
    await settle();
    p.document.querySelector('article').insertAdjacentHTML('beforeend', '<div data-testid="tweetPhoto"><img></div>');
    await settle();
    p.window.history.pushState({}, '', '/user/status/333');
    p.document.body.insertAdjacentHTML('beforeend', tweet('333'));
    await settle();
    assert.deepEqual(p.messages, [
      { url: 'https://x.com/user/status/111', hasMedia: false },
      { url: 'https://x.com/user/status/111', hasMedia: true },
      { url: 'https://x.com/user/status/333', hasMedia: false },
    ]);
  });

  test('logged-out X: finds the focal tweet by its links and media by X\'s media CDN', async () => {
    // Shape of the logged-out mobile web app (no data-testid, no <time>), captured from x.com.
    const post = (id, media) =>
      `<article class="flex flex-col gap-1"><a href="/user"><img src="https://pbs.twimg.com/profile_images/1/a_norm.jpg"></a>` +
      `<a href="https://m.x.com/user/status/${id}?launch_app_store=true&ct=engagement_view_post">view</a>${media}</article>`;
    const reply = post('222', '<a href="/r/status/222/photo/1"><img src="https://pbs.twimg.com/media/R?format=webp&name=large"></a>');
    const video = probe('https://x.com/user/status/111', post('111', '<video src="blob:https://x.com/v"></video>') + reply);
    const text = probe('https://x.com/user/status/111', post('111', '<p>just text</p>') + reply);
    const photo = probe('https://x.com/r/status/222', post('111', '<video></video>') + reply);
    await settle();
    assert.equal(video.messages.at(-1).hasMedia, true);
    assert.equal(text.messages.at(-1).hasMedia, false, 'media of a reply below does not count');
    assert.equal(photo.messages.at(-1).hasMedia, true, 'the parent tweet above is not the focal one');
  });

  test('does not repeat an unchanged answer', async () => {
    const p = probe('https://x.com/user/status/111', tweet('111'));
    await settle();
    p.document.body.insertAdjacentHTML('beforeend', '<div>unrelated</div>');
    await settle();
    assert.equal(p.messages.length, 1);
  });

  /** Counts the probe's focal-tweet scans (each check queries every article once). */
  function countScans(p) {
    let scans = 0;
    const original = p.document.querySelectorAll.bind(p.document);
    p.document.querySelectorAll = (selector) => {
      if (selector === 'article') scans++;
      return original(selector);
    };
    return () => scans;
  }

  /** Mutates the page every 50 ms for [ms], like a timeline loading replies. */
  async function churn(p, ms) {
    for (let elapsed = 0; elapsed < ms; elapsed += 50) {
      p.document.body.insertAdjacentHTML('beforeend', '<div>reply</div>');
      await tick(50);
    }
  }

  test('stops checking once media is confirmed in the focal tweet', async () => {
    const p = probe('https://x.com/user/status/111', tweet('111', '<div data-testid="tweetPhoto"><img></div>'));
    await settle();
    const scans = countScans(p);
    await churn(p, 1000);
    assert.equal(scans(), 0);
    assert.deepEqual(p.messages, [{ url: 'https://x.com/user/status/111', hasMedia: true }]);
  });

  test('backs off on busy pages while the answer is unchanged, and still sees late media', async () => {
    const p = probe('https://x.com/user/status/111', tweet('111'));
    await settle();
    const scans = countScans(p);
    await churn(p, 2000);
    // Without back-off a 300 ms debounce would rescan about 6 times in 2 s.
    assert.ok(scans() <= 3, `scanned ${scans()} times`);
    p.document.querySelector('article').insertAdjacentHTML('beforeend', '<div data-testid="tweetPhoto"><img></div>');
    await tick(2600);
    assert.deepEqual(p.messages.at(-1), { url: 'https://x.com/user/status/111', hasMedia: true });
  });

  test('a URL change after back-off is checked at full speed', async () => {
    const p = probe('https://x.com/user/status/111', tweet('111'));
    await settle();
    await churn(p, 2000);
    p.window.history.pushState({}, '', '/user/status/333');
    p.document.body.insertAdjacentHTML('beforeend', tweet('333', '<video></video>'));
    await settle();
    assert.deepEqual(p.messages.at(-1), { url: 'https://x.com/user/status/333', hasMedia: true });
  });

  test('returning to a settled post reports it again', async () => {
    const p = probe('https://x.com/user/status/111', tweet('111', '<div data-testid="tweetPhoto"><img></div>'));
    await settle();
    p.window.history.pushState({}, '', '/user/status/333');
    p.document.body.insertAdjacentHTML('beforeend', tweet('333'));
    await settle();
    p.window.history.pushState({}, '', '/user/status/111');
    p.document.body.insertAdjacentHTML('beforeend', '<div>re-render</div>');
    await settle();
    assert.deepEqual(p.messages, [
      { url: 'https://x.com/user/status/111', hasMedia: true },
      { url: 'https://x.com/user/status/333', hasMedia: false },
      { url: 'https://x.com/user/status/111', hasMedia: true },
    ]);
  });

  test('threads: content images count, avatars do not', async () => {
    const post = (media) =>
      `<div data-pressable-container="true"><img alt="user's profile picture" width="36" height="36">` +
      `<a href="/@user/post/Abc"><time>1h</time></a>${media}</div>`;
    const text = probe('https://www.threads.net/@user/post/Abc', post(''));
    // Link previews: Meta's external image proxy inside an l.threads.com link.
    const link = probe('https://www.threads.net/@user/post/Abc', post(
      '<a href="https://l.threads.com/?u=https%3A%2F%2Fexample.com"><img src="https://external-hbe1-2.xx.fbcdn.net/emg1/x.jpg" width="388" height="194"></a>'));
    const photo = probe('https://www.threads.net/@user/post/Abc', post('<picture><img src="p.jpg" width="400" height="300"></picture>'));
    await settle();
    assert.equal(text.messages.at(-1).hasMedia, false);
    assert.equal(link.messages.at(-1).hasMedia, false, 'link preview thumbnails are not post media');
    assert.equal(photo.messages.at(-1).hasMedia, true);
  });

  test('facebook: looks at the opened post', async () => {
    const p = probe(
      'https://www.facebook.com/page/posts/pfbid0abc',
      '<div role="main"><div role="article"><video></video></div></div>',
    );
    await settle();
    assert.equal(p.messages.at(-1).hasMedia, true);
  });

  test('stays silent in subframes and without the listener', async () => {
    const page = createPage({ url: 'https://x.com/user/status/1' });
    assert.doesNotThrow(() => page.window.eval(PROBE));
    await settle();
  });
});
