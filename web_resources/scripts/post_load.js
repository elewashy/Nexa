// script.js - Injected on onPageFinished
(function () {
  if (window.location.hostname.includes("freex2line.online")) return;

  // ---- HELPER FUNCTIONS ----

  // Helper to completely isolate an element, center it, and optionally process it
  function isolateElement(selector, onIsolated) {
    var el =
      typeof selector === "string"
        ? document.querySelector(selector)
        : selector;
    if (el) {
      document.body.innerHTML = "";
      document.body.appendChild(el);

      // Apply centering styles
      document.body.style.display = "flex";
      document.body.style.flexDirection = "column";
      document.body.style.justifyContent = "center";
      document.body.style.alignItems = "center";
      document.body.style.height = "100vh";
      document.body.style.margin = "0";
      document.body.style.backgroundColor = "#f9f9f9";

      if (onIsolated) onIsolated(el);
      return true;
    }
    return false;
  }

  function styleButton(btn) {
    btn.style.backgroundColor = "#1e88e5";
    btn.style.color = "#fff";
    btn.style.padding = "20px 40px";
    btn.style.fontSize = "20px";
    btn.style.border = "none";
    btn.style.borderRadius = "8px";
    btn.style.cursor = "pointer";
    btn.style.boxShadow = "0 0 20px rgba(30,136,229,0.6)";
    btn.style.textAlign = "center";
    btn.style.display = "inline-block";
    btn.style.textDecoration = "none";
    btn.style.fontFamily = "Arial, sans-serif";

    btn.onmouseover = function () {
      btn.style.backgroundColor = "#1565c0";
    };
    btn.onmouseout = function () {
      btn.style.backgroundColor = "#1e88e5";
    };
  }
  // ---- AD BLOCKING & CLEANUP ----

  function removeAnnoyances() {
    // Remove known ad selectors to free memory
    var ads = document.querySelectorAll(`
            .pm-ads-banner, .ad-container, .singular--bg, .telegram_themexCom, 
            .comp-hide.AlbaE3lan.table_top, .separator, .banner-inner, .ad-unit, 
            .hydratv, #tme, #aplr-notic, #adsx, #hidx, #ad_position_box, #rewardModal, 
            #tme-alert, iframe[src^="data:text/html"], div[style*="text-align: center;padding: 20px 0 10px;"],
            div[style*="text-align: center;padding: 0 0 30px;"], div[style*="text-align: center;padding: 20px 0 0;"],
            .banner, .ad, #lm-slideup, #popup, #ad-popup, #ad-container, #fixedban5, 
            #popupOverlay, #w3c5, #Advert1, #ad-unit-1, #adContainer, #adsLionz,
            section--titles, .Section--Titles, .live-ad-container, .afcceb-bebeea, .fjojw-ihdwiiwd, .swal2-container,
            a[href="https://tinyurl.com/lionzlink"], a[href*="arablionztv.ink"], #appStickyBanner, .app-install-promo,
            .buttonPress-1077, a[class^="buttonPress-"], script[src*="tfnvuckb.pro"], #sylive-banner
        `);
    ads.forEach(function (el) {
      if (el) el.remove();
    });

    // Targeted removal for elements labeled as advertisement (Arabic and English)
    document.querySelectorAll("div, span, p, a, button").forEach(function (el) {
      var text = (el.textContent || "").trim();
      var keywords = [
        "إعلان",
        "إعلانات",
        "Advertisement",
        "Ads",
        "Promoted",
        "Sponsor",
      ];
      if (keywords.some((k) => text === k)) {
        // If the element has a small parent or is isolated, it's likely a label or ad block
        if (el.parentElement && el.parentElement.children.length <= 3) {
          el.parentElement.remove();
        } else {
          el.remove();
        }
      }
    });

    // Specific class removals from body
    if (document.body) {
      document.body.classList.remove(
        "post-template-default",
        "single",
        "single-post",
        "postid-2288",
        "single-format-standard",
        "right-sidebar",
        "post-layout-modern",
        "post-cat-10",
        "has-lb",
        "has-lb-sm",
        "has-sb-sep",
        "layout-normal",
        "-style-compact",
        "-blur",
        "vsc-initialized",
        "afcceb-dbafdacfcb",
      );
    }
  }
  removeAnnoyances();
  // Run repeatedly to catch late-loading ads/popups
  var annoyanceInterval = setInterval(removeAnnoyances, 2000);
  setTimeout(() => clearInterval(annoyanceInterval), 20000);

  // ---- DOMAIN SPECIFIC LOGICS ----
  var hostname = window.location.hostname;
  var href = window.location.href;

  // Animezid links
  if (
    hostname.includes("animezid.show") ||
    hostname.includes("animezid.cam") ||
    hostname.includes("animezid.cc")
  ) {
    var playUrl = href.replace("/watch.", "/play.");
    var bibPlayerLinks = document.querySelectorAll(
      "#BiBplayer a, .d-grid.gap-2 a",
    );
    bibPlayerLinks.forEach((link) => {
      link.href = playUrl;
      link.removeAttribute("onclick");
    });
  }

  // TukTukHD / TukTukCinema theme link fix (Smart External Links)
  // Fixes links traps in data-real-url or Chrome Intent protocols
  document
    .querySelectorAll("a.smart-external-link[data-real-url]")
    .forEach(function (link) {
      var realUrl = link.getAttribute("data-real-url");
      if (realUrl) {
        // Force normal URL behavior and remove intent traps
        link.href = realUrl;
        link.classList.remove("smart-external-link"); // Remove the target for their internal JS
        link.removeAttribute("onclick");

        // Re-enable clicks on child elements if the site disabled them
        var innerItem = link.querySelector(".download--item");
        if (innerItem) {
          innerItem.style.pointerEvents = "auto";
          innerItem.style.cursor = "pointer";
        }
      }
    });

  // Ugeen live codes (relies on window.__ugeenCodesPromise set in script_2.js)
  if (hostname === "ugeen.live" && window.__ugeenCodesPromise) {
    window.__ugeenCodesPromise.then((uniqueCodesMap) => {
      const linkElement = document.querySelector(
        "a.header-button.navActionDownload",
      );
      if (!linkElement || !linkElement.href) return;
      const matchingCode = uniqueCodesMap.get(linkElement.href);
      if (matchingCode) {
        const codeInput = document.querySelector("#code");
        const activateBtn = document.querySelector("#snd");
        if (codeInput) codeInput.value = matchingCode;
        if (activateBtn) setTimeout(() => activateBtn.click(), 200);
      }
    });
  }

  // Nitro link / Swiftlnx headers hiding
  if (
    href === "https://nitro-link.com/KnIw" ||
    href === "https://swiftlnx.com/EgyFilm_Code" ||
    href === "https://best-cash.net/EgyFilmCode"
  ) {
    var h = document.querySelector("header");
    var f = document.querySelector("footer");
    if (h) h.style.display = "none";
    if (f) f.style.display = "none";
  }

  // Telegram FAQ link toggle
  if (href === "https://telegram.org/apps") {
    const links = ["https://swiftlnx.com/EgyFilm_Code"];
    let currentIndex = parseInt(localStorage.getItem("linkToggleIndex") || "0");
    const currentLink = links[currentIndex % links.length];
    localStorage.setItem(
      "linkToggleIndex",
      ((currentIndex + 1) % links.length).toString(),
    );

    document.body.innerHTML = "";
    const btn = document.createElement("button");
    btn.textContent = "اضغط للمتابعة";
    styleButton(btn);
    btn.style.position = "fixed";
    btn.style.top = "50%";
    btn.style.left = "50%";
    btn.style.transform = "translate(-50%, -50%)";
    btn.onclick = () => (window.location.href = currentLink);
    document.body.appendChild(btn);
    btn.click();
  }

  // Cima Now video server selection
  (function () {
    const watchList = document.querySelector("#watch");
    if (!watchList || watchList.querySelector('li[data-index="00"]')) return;
    const otherServer = watchList.querySelector("li[data-id]");
    if (!otherServer) return;

    const dataId = otherServer.getAttribute("data-id");
    const cimaNowLi = document.createElement("li");
    cimaNowLi.setAttribute("data-index", "00");
    cimaNowLi.setAttribute("data-id", dataId);
    cimaNowLi.textContent = "Cima Now";
    cimaNowLi.className = otherServer.className;

    cimaNowLi.addEventListener("click", function () {
      document
        .querySelectorAll("#watch li[data-id]")
        .forEach((li) => li.classList.remove("active"));
      this.classList.add("active");

      fetch(
        `https://cimanow.cc/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=00&id=${dataId}`,
      )
        .then((res) => res.text())
        .then((response) => {
          const doc = new DOMParser().parseFromString(response, "text/html");
          const newIframe = doc.querySelector("iframe");
          const embedLi = document.querySelector(
            '#watch li[aria-label="embed"]',
          );
          if (embedLi && newIframe) {
            embedLi.innerHTML = "";
            embedLi.appendChild(newIframe);
          }
        });
    });
    watchList.insertBefore(cimaNowLi, watchList.firstChild);
  })();

  // ---- UI MODIFICATIONS & ISOLATIONS ----

  // Specific button modifications or clicking
  var myBtn = document.getElementById("myButton");
  if (myBtn) {
    myBtn.style.display = "none";
    var newBtn = document.createElement("button");
    newBtn.id = "newButton";
    newBtn.innerText = "تحميل الآن";
    styleButton(newBtn);
    newBtn.onclick = myBtn.onclick || function () {};
    myBtn.parentNode.appendChild(newBtn);
  }

  function bypassDownloadButtons() {
    // download-button bypass
    var dlButton = document.getElementById("download-button");
    if (dlButton) {
      var downloadLoading = document.getElementById("download-loading");
      if (downloadLoading) downloadLoading.style.display = "none";
      dlButton.style.setProperty("display", "inline-block", "important");

      var dlButtonText = document.getElementById("download-button-text");
      if (dlButtonText)
        dlButtonText.style.setProperty("display", "inline-block", "important");

      var dataHref = dlButton.getAttribute("data-href");
      if (dataHref) {
        try {
          dlButton.href = atob(dataHref);
        } catch (e) {
          dlButton.href = dataHref;
        }
      }
    }

    // d-button bypass
    var dBtn = document.getElementById("d-button");
    if (dBtn) {
      var dLoading = document.getElementById("d-loading");
      if (dLoading) dLoading.style.display = "none";
      dBtn.style.setProperty("display", "inline-block", "important");

      var dBtnText = document.getElementById("d-button-text");
      if (dBtnText)
        dBtnText.style.setProperty("display", "inline-block", "important");

      var dDataHref = dBtn.getAttribute("data-href");
      if (dDataHref) {
        try {
          dBtn.href = atob(dDataHref);
        } catch (e) {
          dBtn.href = dDataHref;
        }
      }
    }
  }
  bypassDownloadButtons();
  var bypassInterval = setInterval(bypassDownloadButtons, 1000);
  setTimeout(() => clearInterval(bypassInterval), 20000);

  // "Get Link" auto clicker
  var getLinkInterval = setInterval(() => {
    var btn = document.querySelector("a.get-link:not(.disabled)");
    if (btn && btn.textContent.trim().toLowerCase() === "get link") {
      clearInterval(getLinkInterval);
      btn.click();
    }
  }, 500);

  // Isolate Center Oto
  if (
    isolateElement("center.oto", function (el) {
      var pb = document.getElementById("progressBarContainer");
      var nb = document.getElementById("nextbutton");
      if (pb) pb.style.display = "block";
      if (nb) {
        styleButton(nb);
        nb.removeAttribute("disabled");
        nb.click();
      }
    })
  )
    return;

  // Isolate wpsafelink-landing
  if (
    isolateElement("#wpsafelink-landing", function (el) {
      var btn = document.querySelector("#wpsafelinkhuman");
      if (btn) {
        styleButton(btn);
        btn.click();
      }
    })
  )
    return;

  // Isolate downloadContainer10
  if (
    isolateElement(
      ".mt-4.flex.justify-center.items-center.flex-col",
      function (el) {
        var b = el.querySelector("[download-button]");
        if (b) {
          b.classList.remove("hidden");
          styleButton(b);
        }
      },
    )
  )
    return;

  // Isolate #form-container form
  var wrapper = document.querySelector(".wrapper");
  if (wrapper) {
    var form = wrapper.querySelector("#form-container form");
    if (
      isolateElement(form, function (el) {
        var b = el.querySelector("button[type='submit']");
        if (b) styleButton(b);
      })
    )
      return;
  }

  // Isolate form[name='tp'] or #btn6
  var formTp = document.querySelector("form[name='tp']");
  var btn6 = document.querySelector("#btn6");
  if (formTp) {
    isolateElement(formTp, function () {
      if (btn6) styleButton(btn6);
    });
    return;
  } else if (btn6) {
    var parentLink = btn6.closest("a") || btn6;
    isolateElement(parentLink, function () {
      styleButton(btn6);
    });
    return;
  }

  // Isolate hmVrfy
  if (
    isolateElement("#hmVrfy", function (el) {
      var nBtn = el.querySelector("a.button.pstL");
      if (nBtn) {
        styleButton(nBtn);
        nBtn.style.display = "none";
        setTimeout(() => {
          el.querySelectorAll("button, a:not(.pstL)").forEach(
            (b) => (b.style.display = "none"),
          );
          nBtn.style.display = "block";
        }, 5000);
      }
    })
  )
    return;

  // Isolate go_down
  if (
    isolateElement("#go_down", function (el) {
      var loadContainer = document.getElementById("loadingBarContainer");
      var goD = document.getElementById("go_d");
      if (loadContainer) loadContainer.style.display = "block";
      if (goD) {
        styleButton(goD);
        goD.click();
      }
    })
  )
    return;

  // Isolate loading-screen or getLinkButton
  var loadingScreen = document.getElementById("loading-screen");
  var getLBtn = document.querySelector("a#yuidea-btmbtn");
  var hasLS = loadingScreen && loadingScreen.querySelector("button[onclick]");
  if (hasLS || getLBtn) {
    document.body.innerHTML = "";
    document.body.style.display = "flex";
    document.body.style.flexDirection = "column";
    document.body.style.justifyContent = "center";
    document.body.style.alignItems = "center";
    document.body.style.height = "100vh";
    document.body.style.margin = "0";
    document.body.style.backgroundColor = "#f9f9f9";

    let contBtn = null;
    if (hasLS) {
      document.body.appendChild(loadingScreen);
      contBtn = loadingScreen.querySelector("#continue-button");
      if (contBtn) {
        contBtn.disabled = false;
        styleButton(contBtn);
      }
    }
    if (getLBtn) {
      document.body.appendChild(getLBtn);
      var destBtn = getLBtn.querySelector("button") || getLBtn;
      styleButton(destBtn);
    }
    if (contBtn) contBtn.click();
    if (getLBtn) getLBtn.click();
    return;
  }

  // Isolate secondSection (hidden section)
  var secSection = document.getElementById("secondSection");
  if (secSection) {
    secSection.classList.remove("hidden");
    isolateElement(secSection, function (el) {
      el.classList.remove("b0g-white");
      el.classList.add("bg-white");
      var b = el.querySelector("a");
      if (b) {
        styleButton(b);
        b.click();
      }
    });
    return;
  }

  // Isolate wpsafe-link
  var safeLink = document.getElementById("wpsafe-link");
  if (safeLink) {
    isolateElement(safeLink, function (el) {
      styleButton(el);
      var a = el.querySelector("a");
      if (a) a.click();
    });
    return;
  }

  // Isolate redirectBtn
  var redirectBtn = document.getElementById("redirectBtn");
  if (redirectBtn) {
    isolateElement(redirectBtn, function (el) {
      styleButton(el);
      var a = el.querySelector("a");
      if (a) {
        a.click();
        a.click();
      }
    });
    return;
  }

  // Isolate safeGoL button
  var safeGoL = document.querySelector("a.button.safeGoL");
  if (safeGoL) {
    var hrefLink = safeGoL.href;
    document.body.innerHTML = "";
    var newBtn = document.createElement("a");
    newBtn.href = hrefLink;
    newBtn.textContent = "Go to Link";
    styleButton(newBtn);
    newBtn.style.position = "fixed";
    newBtn.style.top = "50%";
    newBtn.style.left = "50%";
    newBtn.style.transform = "translate(-50%, -50%)";
    document.body.appendChild(newBtn);
    return;
  }
  // DownloadMainContent bypass (in-place)
  var dlMainContent = document.querySelector(".DownloadMainContent");
  if (dlMainContent) {
    var clickMe = dlMainContent.querySelector("#clickme");
    var finalBtn = dlMainContent.querySelector("#btn");

    if (clickMe) clickMe.style.display = "none";

    if (finalBtn) {
      finalBtn.style.setProperty("display", "inline-block", "important");
    }
  }
})();
  // Isolate countdown and download button (CimaNow style)
  (function () {
    if (!location.hostname.includes("freex2line.online")) return;
    const style = document.createElement("style");
    style.innerHTML = `
      body {
        display: flex !important;
        justify-content: center !important;
        align-items: center !important;
        height: 100vh !important;
        margin: 0 !important;
      }

      #countdown,
      .text_sco,
      #downloadbtn {
        text-align: center !important;
      }
    `;
    document.head.appendChild(style);
  })();
