# Bundled JS tests

Node tests for the scriptlet library and content script bundled in
`app/src/main/assets/adblock/`, and for the page media probe in
`app/src/main/assets/media/`. They load the assets into [jsdom] the way
`AdBlockAssets` and `PageMediaProbe` inject them into WebView.

```sh
cd app/src/test/js
npm ci
npm test
```

- `scriptlets.test.mjs` — one test per scriptlet; fails when a scriptlet is
  registered without a test.
- `core.test.mjs` — patch cloaking, error suppression, the JSONPath engine.
- `content.test.mjs` — payload handling, `$csp` meta policies, procedural
  operators.
- `media-probe.test.mjs` — focal-post scoping, change-only reporting and
  back-off on busy pages of the download button's media probe.

jsdom's XPath engine supports neither `name()` nor the attribute axis, so
`xml-prune` attribute expressions are only exercised in a real WebView.

[jsdom]: https://github.com/jsdom/jsdom
