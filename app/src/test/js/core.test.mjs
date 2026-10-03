/* Shared helpers: patch cloaking, error suppression and the JSONPath engine. */
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { setupPage } from './harness.mjs';

const plain = (v) => JSON.parse(JSON.stringify(v));

describe('cloaking', () => {
  test('patched functions and accessors stringify like the originals', () => {
    const p = setupPage();
    const w = p.window;
    const openSource = w.Function.prototype.toString.call(w.open);
    const parseSource = w.Function.prototype.toString.call(w.JSON.parse);
    const toStringSource = w.Function.prototype.toString.call(w.Function.prototype.toString);
    p.run('no-window-open-if', 'ads');
    p.run('json-prune', 'ads');
    p.run('set-constant', 'flag', 'true');
    p.H.installToStringCloak();
    // Page scripts stringify through the page's Function.prototype.toString.
    // (jsdom creates some natives such as `open` in Node's realm, so the
    // method is looked up explicitly rather than via `fn.toString()`.)
    const pageToString = w.Function.prototype.toString;
    assert.equal(pageToString.call(w.open), openSource);
    assert.equal(pageToString.call(w.JSON.parse), parseSource);
    assert.equal(pageToString.call(pageToString), toStringSource);
    const getter = Object.getOwnPropertyDescriptor(w, 'flag').get;
    assert.equal(pageToString.call(getter), 'function get flag() { [native code] }');
    // Unrelated functions are untouched.
    w.eval('function pageFn() { return 1; }');
    assert.equal(w.pageFn.toString(), 'function pageFn() { return 1; }');
  });

  test('accessors keep the native source they wrap, new ones read as native accessors', () => {
    const p = setupPage();
    const w = p.window;
    const pageToString = () => w.Function.prototype.toString;
    const nativeGetter = Object.getOwnPropertyDescriptor(w.XMLHttpRequest.prototype, 'responseText').get;
    const nativeGetterSource = w.Function.prototype.toString.call(nativeGetter);
    p.run('trusted-replace-xhr-response', 'a', 'b', '/x');
    p.run('set-constant', 'adsFlag', 'false');
    p.H.installToStringCloak();
    const wrapped = Object.getOwnPropertyDescriptor(w.XMLHttpRequest.prototype, 'responseText').get;
    assert.notEqual(wrapped, nativeGetter);
    assert.equal(pageToString().call(wrapped), nativeGetterSource);
    const trap = Object.getOwnPropertyDescriptor(w, 'adsFlag');
    assert.equal(pageToString().call(trap.get), 'function get adsFlag() { [native code] }');
    assert.equal(pageToString().call(trap.set), 'function set adsFlag() { [native code] }');
  });

  test('abort traps register their error listener before page listeners', () => {
    const p = setupPage();
    const w = p.window;
    p.run('abort-on-property-read', 'detector');
    const seen = [];
    w.addEventListener('error', (e) => seen.push(e.message)); // page listener, added after setup
    w.dispatchEvent(new w.ErrorEvent('error', { message: 'Uncaught ReferenceError: ' + p.H.magic, cancelable: true }));
    assert.deepEqual(seen, []);
  });

  test('abort errors are swallowed before page error listeners see them', () => {
    const p = setupPage();
    const w = p.window;
    p.H.installErrorHandler();
    const seen = [];
    w.addEventListener('error', (e) => seen.push(e.message));
    const ours = new w.ErrorEvent('error', { message: 'Uncaught ReferenceError: ' + p.H.magic, cancelable: true });
    w.dispatchEvent(ours);
    const theirs = new w.ErrorEvent('error', { message: 'page bug', cancelable: true });
    w.dispatchEvent(theirs);
    assert.equal(ours.defaultPrevented, true);
    assert.deepEqual(seen, ['page bug']);
    assert.equal(w.onerror, null, 'window.onerror is not hijacked');
  });
});

describe('JsonPath', () => {
  const apply = (query, obj) => {
    const p = setupPage();
    const jp = p.JsonPath.create(query);
    assert.ok(jp.valid, `valid: ${query}`);
    const out = jp.apply(plain(obj)); // queries edit in place: work on a copy
    return out === undefined ? undefined : plain(out);
  };

  test('child, descendant, wildcard and bracket selectors remove what they select', () => {
    assert.deepEqual(apply('.a.b', { a: { b: 1, c: 2 } }), { a: { c: 2 } });
    assert.deepEqual(apply('..ad', { x: { ad: 1, y: { ad: 2, z: 3 } } }), { x: { y: { z: 3 } } });
    assert.deepEqual(apply('.list.*', { list: [1, 2] }), { list: [] });
    assert.deepEqual(apply("['odd key'][0]", { 'odd key': [1, 2] }), { 'odd key': [2] });
    assert.deepEqual(apply('.list[-1]', { list: [1, 2, 3] }), { list: [1, 2] });
    assert.deepEqual(apply('.o[a,b]', { o: { a: 1, b: 2, c: 3 } }), { o: { c: 3 } });
    assert.equal(apply('.missing', { a: 1 }), undefined, 'no match leaves the input untouched');
  });

  test('filters test objects, or array elements, with comparisons and negation', () => {
    const items = { items: [{ t: 'ad', n: 1 }, { t: 'video', n: 5 }, { t: 'ad-x', n: 9 }] };
    assert.deepEqual(apply('.items.*[?.t=="ad"]', items), { items: [{ t: 'video', n: 5 }, { t: 'ad-x', n: 9 }] });
    assert.deepEqual(apply('.items[?.t^="ad"]', items), { items: [{ t: 'video', n: 5 }] });
    assert.deepEqual(apply('.items[?.n>4]', items), { items: [{ t: 'ad', n: 1 }] });
    assert.deepEqual(apply('.items[?!.t*="ad"]', items), { items: [{ t: 'ad', n: 1 }, { t: 'ad-x', n: 9 }] });
    assert.deepEqual(apply('.items[?.t=/^v/]', items), { items: [{ t: 'ad', n: 1 }, { t: 'ad-x', n: 9 }] });
    assert.deepEqual(apply('[?.kind=="ads"].visibility', { kind: 'ads', visibility: 1, k: 2 }), { kind: 'ads', k: 2 });
    assert.equal(apply('[?.kind=="ads"].visibility', { kind: 'news', visibility: 1 }), undefined);
  });

  test('assignments, merges, repl and ${now}', () => {
    assert.deepEqual(apply('..enabled=false', { a: { enabled: true }, b: [{ enabled: 1 }] }), { a: { enabled: false }, b: [{ enabled: false }] });
    assert.deepEqual(apply('$+={"x":1}', { y: 2 }), { y: 2, x: 1 });
    assert.deepEqual(apply('.s=repl({"pattern":"ad","replacement":"--"})', { s: 'bad ad' }), { s: 'b-- ad' });
    assert.match(String(apply('.t="${now}"', { t: 0 }).t), /^\d{13}$/);
  });

  test('v2 regex keys and invalid queries', () => {
    assert.deepEqual(apply('v2:$./^adv_/', { adv_a: 1, adv_b: 2, keep: 3 }), { keep: 3 });
    const p = setupPage();
    for (const bad of ['', '$', '.a=', '.a[?]', '.a=not json', '.a[?.b', '+=1']) {
      assert.equal(p.JsonPath.create(bad).valid, false, `invalid: ${bad}`);
    }
  });
});
