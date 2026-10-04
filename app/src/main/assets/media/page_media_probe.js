/*
 * Page media probe (document-start, top frame only).
 *
 * Registered by PageMediaProbe for the origins of platforms whose content
 * pages may or may not carry media (X/Twitter, Threads, Facebook). It
 * reports, through the `__NEXA_PROBE__` web message listener, whether the
 * content the page URL points at (the focal tweet, the opened Threads post,
 * the Facebook post) renders a video or a content image, so the browser
 * shows its download button only when there is something to download.
 * For X, the platform's own data takes precedence when available; this
 * probe covers tweets that data does not serve.
 *
 * Event-driven and cheap: DOM mutations, resource loads and history changes
 * schedule one debounced check; a message is posted only when the answer for
 * the current URL changes. Pages that keep mutating (timelines loading
 * replies) are checked less and less often while the answer stays the same,
 * and not at all once media is confirmed in the focal post itself; a URL
 * change restarts checking at full speed.
 */
(function () {
  'use strict';
  if (window.top !== window) return;
  var port = window.__NEXA_PROBE__;
  if (!port || typeof port.postMessage !== 'function') return;
  var post = port.postMessage.bind(port);

  var CHECK_DELAY_MS = 300;
  /** Ceiling of the back-off applied while the answer for a URL does not change. */
  var MAX_CHECK_DELAY_MS = 2400;
  /** Minimum rendered (or intrinsic) edge of an image that counts as content, in CSS px. */
  var MIN_IMAGE_EDGE = 120;
  var MEDIA_SELECTOR = 'video,[data-testid="videoPlayer"],[data-testid="videoComponent"],[data-testid="tweetPhoto"]';

  var lastReport = null;
  var timer = 0;
  var delay = CHECK_DELAY_MS;
  /** URL the scheduling state belongs to; a different URL resets it. */
  var currentUrl = null;
  /** URL whose focal post was found with media; not checked again until the URL changes. */
  var settledUrl = null;
  /**
   * Set by a detector when it located the focal post itself (by a link to
   * the URL's own content) rather than through a positional fallback, so a
   * positive answer for it cannot be a neighbouring post's media.
   */
  var focalConfirmed = false;

  function idFrom(pattern) {
    var match = pattern.exec(location.pathname);
    return match ? match[1] : null;
  }

  function hrefMatches(anchor, pattern) {
    var href = anchor && anchor.getAttribute('href');
    return !!href && pattern.test(href);
  }

  /** Meta's link-preview image proxy and its outbound link redirectors. */
  var LINK_PREVIEW_IMAGE = /^https:\/\/external[-.]/;
  var LINK_PREVIEW_ANCHOR = 'a[href*="//l.threads.com/"],a[href*="//l.facebook.com/"],a[href*="//lm.facebook.com/"]';

  /** A large image that is not an avatar, an emoji, or a link preview's thumbnail. */
  function isContentImage(img) {
    if (/profile (picture|photo)|avatar/i.test(img.getAttribute('alt') || '')) return false;
    if (LINK_PREVIEW_IMAGE.test(img.currentSrc || img.src || '') || img.closest(LINK_PREVIEW_ANCHOR)) return false;
    var rect = img.getBoundingClientRect();
    var edge = Math.max(rect.width, rect.height, img.width || 0, img.height || 0);
    var natural = Math.max(img.naturalWidth || 0, img.naturalHeight || 0);
    return edge >= MIN_IMAGE_EDGE || natural >= MIN_IMAGE_EDGE * 2;
  }

  /** Video players and platform-tagged photos in [root]. */
  function hasMediaElement(root) {
    return !!root && !!root.querySelector(MEDIA_SELECTOR);
  }

  /** Media elements or, for platforms without photo markers, any content-sized image. */
  function hasMedia(root) {
    if (hasMediaElement(root)) return true;
    var images = root ? root.querySelectorAll('img') : [];
    for (var i = 0; i < images.length; i++) if (isContentImage(images[i])) return true;
    return false;
  }

  /** X media: players, tagged photos, and images served from X's media CDN (not avatars or link cards). */
  var TWEET_MEDIA_IMAGE = /^https:\/\/pbs\.twimg\.com\/(media|ext_tw_video_thumb|amplify_video_thumb|tweet_video_thumb)\//;

  /** A quoted tweet: a nested link container with its own author line. */
  function inQuotedTweet(el, focal) {
    var quote = el.closest('[role="link"]');
    return !!quote && quote !== focal && focal.contains(quote) && !!quote.querySelector('[data-testid="User-Name"]');
  }

  /**
   * The focal tweet is the first article that links to the status in the
   * URL (its timestamp or engagement links). Replies and parent tweets link
   * to their own statuses. Works with both the logged-in and the logged-out
   * X web apps, which share no other markup.
   */
  function twitter() {
    var id = idFrom(/\/status(?:es)?\/(\d+)/);
    if (!id) return false;
    var own = new RegExp('/status(?:es)?/' + id + '(?:$|[/?#])');
    var focal = null;
    var articles = document.querySelectorAll('article');
    for (var i = 0; i < articles.length && !focal; i++) {
      var links = articles[i].querySelectorAll('a[href]');
      for (var j = 0; j < links.length; j++) {
        if (hrefMatches(links[j], own) && !inQuotedTweet(links[j], articles[i])) {
          focal = articles[i];
          focalConfirmed = true;
          break;
        }
      }
    }
    focal = focal || document.querySelector('article[tabindex="-1"]');
    if (!focal) return false;

    var media = focal.querySelectorAll('video,img,' + MEDIA_SELECTOR);
    for (var k = 0; k < media.length; k++) {
      var el = media[k];
      if (inQuotedTweet(el, focal)) continue;
      if (el.tagName !== 'IMG' || TWEET_MEDIA_IMAGE.test(el.currentSrc || el.src || '')) return true;
    }
    return false;
  }

  function threads() {
    var code = idFrom(/\/post\/([A-Za-z0-9_-]+)/);
    if (!code) return false;
    var own = new RegExp('/post/' + code + '(?:$|[/?#])');
    var post = null;
    var times = document.querySelectorAll('a time');
    for (var i = 0; i < times.length && !post; i++) {
      var anchor = times[i].closest('a');
      if (hrefMatches(anchor, own)) post = anchor.closest('[data-pressable-container]');
    }
    focalConfirmed = !!post;
    return hasMedia(post || document.querySelector('[data-pressable-container]'));
  }

  function facebook() {
    var main = document.querySelector('[role="main"]') || document.body;
    return hasMedia(main && (main.querySelector('[role="article"]') || main));
  }

  function detect() {
    var host = location.hostname.replace(/^www\./, '');
    if (/(^|\.)(x|twitter)\.com$/.test(host)) return twitter();
    if (/(^|\.)threads\.(net|com)$/.test(host)) return threads();
    if (/(^|\.)facebook\.com$/.test(host)) return facebook();
    return false;
  }

  function check() {
    timer = 0;
    var url = location.href;
    if (url !== currentUrl) {
      // Navigated while the timer was pending: restart from the base delay for the new URL.
      schedule();
      return;
    }
    var found = false;
    focalConfirmed = false;
    try {
      found = detect();
    } catch (e) {
      found = false;
    }
    if (found && focalConfirmed) settledUrl = url;
    var report = url + '\n' + found;
    if (report === lastReport) {
      delay = Math.min(delay * 2, MAX_CHECK_DELAY_MS);
      return;
    }
    delay = CHECK_DELAY_MS;
    lastReport = report;
    post(JSON.stringify({ url: url, hasMedia: found }));
  }

  function schedule() {
    var url = location.href;
    if (url !== currentUrl) {
      currentUrl = url;
      // The tab keeps only the latest report, so returning to a settled URL must report it again.
      settledUrl = null;
      delay = CHECK_DELAY_MS;
      if (timer) clearTimeout(timer);
      timer = 0;
    }
    if (timer || url === settledUrl) return;
    timer = setTimeout(check, delay);
  }

  new MutationObserver(schedule).observe(document, { childList: true, subtree: true });
  // Image/video loads do not mutate the DOM but change rendered sizes.
  document.addEventListener('load', schedule, true);
  window.addEventListener('popstate', schedule);
  window.addEventListener('pageshow', schedule);
  schedule();
})();
