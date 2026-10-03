/*
 * Test harness: loads the bundled scriptlet library (and optionally the
 * content script) into a jsdom window, the way AdBlockAssets assembles them
 * for WebView, with deterministic fakes for the APIs jsdom lacks (fetch,
 * a scriptable XMLHttpRequest, Clipboard, DataTransfer, Navigation API…).
 */
import { JSDOM, VirtualConsole } from 'jsdom';
import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ASSETS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../main/assets/adblock');
const SCRIPTLET_DIR = path.join(ASSETS, 'scriptlets');

export const LIBRARY = readdirSync(SCRIPTLET_DIR)
  .filter((f) => f.endsWith('.js'))
  .sort()
  .map((f) => readFileSync(path.join(SCRIPTLET_DIR, f), 'utf8'))
  .join('\n');

export const CONTENT_SCRIPT = readFileSync(path.join(ASSETS, 'content.js'), 'utf8');

const DEFAULT_HTML = '<!DOCTYPE html><html><head></head><body></body></html>';

export const tick = (ms = 0) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * A fresh page. [responses] maps URL substrings to response bodies served by
 * the fake fetch()/XMLHttpRequest; [setup] runs before the library loads,
 * i.e. before it captures natives (install spies there).
 */
export function createPage({ url = 'https://site.com/', html = DEFAULT_HTML, responses = {}, setup } = {}) {
  const errors = [];
  const virtualConsole = new VirtualConsole();
  virtualConsole.on('jsdomError', (e) => errors.push(String((e && e.message) || e)));
  const dom = new JSDOM(html, { url, runScripts: 'outside-only', pretendToBeVisual: true, virtualConsole });
  const w = dom.window;
  const network = installFakes(w, responses);
  if (setup) setup(w);
  return { dom, window: w, document: w.document, errors, network };
}

/** Evaluates the library (+ content script) in [page] inside one closure, like the real assembly. */
export function loadLibrary(page, { content = false, bridge } = {}) {
  const w = page.window;
  if (bridge) w.__NEXA_BRIDGE__ = bridge;
  const exportForTests = '\nwindow.__nexaTest = { S: S, H: H, JsonPath: JsonPath };\n';
  w.eval("(function(){'use strict';\n" + LIBRARY + exportForTests + (content ? CONTENT_SCRIPT : '') + '\n})();');
  const lib = w.__nexaTest;
  delete w.__nexaTest;
  return lib;
}

/** Page + library; `run(name, ...args)` invokes a scriptlet like the content script does. */
export function setupPage(options = {}) {
  const page = createPage(options);
  const lib = loadLibrary(page, options);
  return { ...page, ...lib, run: (name, ...args) => lib.S[name].apply(null, args) };
}

function lookup(responses, url) {
  for (const key of Object.keys(responses)) if (url.includes(key)) return responses[key];
  return undefined;
}

function installFakes(w, responses) {
  const network = { fetches: [], xhrs: [] };

  w.Response = Response;
  w.Request = Request;
  w.Headers = Headers;
  w.TextDecoder = TextDecoder;
  w.CSS = { escape: (s) => String(s).replace(/([^\w-])/g, '\\$1') };
  w.fetch = async (input, init) => {
    const url = input instanceof Request ? input.url : String(input);
    network.fetches.push({ url, init });
    const body = lookup(responses, url);
    return new Response(body === undefined ? '' : body, { status: body === undefined ? 404 : 200 });
  };

  // Scriptable XHR exposing response getters on the prototype, like browsers.
  class FakeXMLHttpRequest extends w.EventTarget {
    constructor() {
      super();
      this._state = 0;
      this._text = '';
      this.responseType = '';
      this.status = 0;
    }
    open(method, url) { this._method = method; this._url = String(url); this._state = 1; }
    send(body) {
      network.xhrs.push({ url: this._url, body });
      const text = lookup(responses, this._url);
      setTimeout(() => {
        this._text = text === undefined ? '' : text;
        this.status = text === undefined ? 404 : 200;
        this._state = 4;
        for (const type of ['readystatechange', 'load', 'loadend']) this.dispatchEvent(new w.Event(type));
      }, 0);
    }
    get readyState() { return this._state; }
    get response() {
      if (this._state !== 4) return this.responseType === 'json' ? null : '';
      if (this.responseType === 'json') { try { return JSON.parse(this._text); } catch { return null; } }
      if (this.responseType === 'document') return new w.DOMParser().parseFromString(this._text, 'text/xml');
      return this._text;
    }
    get responseText() {
      if (this.responseType !== '' && this.responseType !== 'text') throw new w.DOMException('InvalidStateError');
      return this._state === 4 ? this._text : '';
    }
    get responseXML() {
      if (this._state !== 4 || !/^\s*</.test(this._text)) return null;
      return new w.DOMParser().parseFromString(this._text, 'text/xml');
    }
  }
  w.XMLHttpRequest = FakeXMLHttpRequest;

  const clipboardWrites = [];
  network.clipboard = clipboardWrites;
  w.Clipboard = class Clipboard {
    writeText(text) { clipboardWrites.push(text); return Promise.resolve(); }
    write(items) {
      return Promise.all(items.map((i) => i.getType('text/plain').then((b) => b.text())))
        .then((texts) => { clipboardWrites.push(...texts); });
    }
  };
  Object.defineProperty(w.navigator, 'clipboard', { configurable: true, value: new w.Clipboard() });
  w.DataTransfer = class DataTransfer {
    constructor() { this.data = {}; }
    setData(format, value) { this.data[format] = value; }
    getData(format) { return this.data[format]; }
  };
  w.Document.prototype.execCommand = function () { return true; };
  w.navigation = new w.EventTarget();
  return network;
}

/** Dispatches a Navigation API `navigate` event; returns whether it was cancelled. */
export function navigate(w, url, { userInitiated = false } = {}) {
  const ev = new w.Event('navigate', { cancelable: true });
  Object.defineProperty(ev, 'userInitiated', { value: userInitiated });
  Object.defineProperty(ev, 'destination', { value: { url } });
  w.navigation.dispatchEvent(ev);
  return ev.defaultPrevented;
}

/** XHR helper resolving with the finished request. */
export function xhr(w, url, { responseType = '', method = 'GET', body } = {}) {
  return new Promise((resolve) => {
    const req = new w.XMLHttpRequest();
    req.responseType = responseType;
    req.addEventListener('load', () => resolve(req));
    req.open(method, url);
    req.send(body);
  });
}
