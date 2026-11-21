// Everything here is optional: the page is complete without JavaScript.
(function () {
  'use strict';

  var REPO = 'denissteinhorst/thor-back-button-hero';

  document.documentElement.classList.add('js');

  // Keep desktop/tablet descriptions visible; phones use the native disclosures.
  var highlightCards = Array.prototype.slice.call(document.querySelectorAll('.highlight-card'));
  var compactHighlights = window.matchMedia('(max-width: 640px)');
  function layoutHighlights() {
    highlightCards.forEach(function (card) { card.open = !compactHighlights.matches; });
  }
  highlightCards.forEach(function (card) {
    card.querySelector('summary').addEventListener('click', function (event) {
      if (!compactHighlights.matches) event.preventDefault();
    });
  });
  layoutHighlights();
  compactHighlights.addEventListener('change', layoutHighlights);

  // Keep every family readable without JavaScript; only enhance once the controls work.
  var inputPicker = document.querySelector('.input-picker');
  var inputButtons = Array.prototype.slice.call(inputPicker.querySelectorAll('button'));
  function selectInput(button) {
    inputButtons.forEach(function (candidate) {
      var selected = candidate === button;
      candidate.setAttribute('aria-pressed', String(selected));
      document.getElementById(candidate.getAttribute('aria-controls')).hidden = !selected;
    });
  }
  inputButtons.forEach(function (button) {
    button.addEventListener('click', function () { selectInput(button); });
  });
  selectInput(inputButtons[0]);
  inputPicker.hidden = false;

  var modifierPicker = document.querySelector('.modifier-picker');
  var modifierButtons = Array.prototype.slice.call(modifierPicker.querySelectorAll('button'));
  var modifierNames = Array.prototype.slice.call(document.querySelectorAll('[data-shortcut-modifier]'));
  var modifierCopy = Array.prototype.slice.call(document.querySelectorAll('[data-shortcut-home]')).map(function (node) {
    return { node: node, back: node.textContent, home: node.getAttribute('data-shortcut-home') };
  });
  var backOnlyInputs = Array.prototype.slice.call(document.querySelectorAll('[data-back-only]'));
  function selectModifier(modifier) {
    modifierButtons.forEach(function (button) {
      button.setAttribute('aria-pressed', String(button.getAttribute('data-modifier') === modifier));
    });
    modifierNames.forEach(function (node) { node.textContent = modifier; });
    modifierCopy.forEach(function (copy) { copy.node.textContent = modifier === 'Home' ? copy.home : copy.back; });
    // Home is the held modifier in Dual mode, with no Home + Home shortcut.
    backOnlyInputs.forEach(function (node) { node.hidden = modifier === 'Home'; });
  }
  modifierButtons.forEach(function (button) {
    button.addEventListener('click', function () { selectModifier(button.getAttribute('data-modifier')); });
  });
  document.querySelector('.shortcut-mode').hidden = false;

  var actionCatalog = document.getElementById('action-catalog');
  function revealFeatureAnchor() {
    if (location.hash === '#actions') actionCatalog.open = true;
    if (location.hash === '#binding-examples') selectInput(inputButtons[0]);
  }
  revealFeatureAnchor();
  window.addEventListener('hashchange', revealFeatureAnchor);
  document.querySelectorAll('a[href="#actions"]').forEach(function (link) {
    link.addEventListener('click', function () { actionCatalog.open = true; });
  });

  // Narrow screens fold the navigation into a menu; without JavaScript it scrolls sideways instead.
  var menuBtn = document.getElementById('menu-toggle');
  var header = document.querySelector('.top');
  if (menuBtn && header) {
    menuBtn.hidden = false;
    var setMenu = function (open) {
      header.classList.toggle('menu-open', open);
      menuBtn.setAttribute('aria-expanded', String(open));
    };
    menuBtn.addEventListener('click', function () { setMenu(!header.classList.contains('menu-open')); });
    document.getElementById('site-nav').addEventListener('click', function (e) { if (e.target.closest('a')) setMenu(false); });
    document.addEventListener('click', function (e) { if (!header.contains(e.target)) setMenu(false); });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && header.classList.contains('menu-open')) { setMenu(false); menuBtn.focus(); }
    });
    // Leaving the narrow layout must not strand an open menu.
    var wide = window.matchMedia('(min-width: 861px)');
    if (wide.addEventListener) wide.addEventListener('change', function () { setMenu(false); });
  }

  // The optional screenshot gallery keeps its chapter navigation and image viewer.
  var shots = Array.prototype.slice.call(document.querySelectorAll('.shot'));
  var chapters = Array.prototype.slice.call(document.querySelectorAll('.shot-chapters button'));
  var count = document.querySelector('.shot-count');
  var current = 0;
  function showShot(index) {
    current = (index + shots.length) % shots.length;
    shots.forEach(function (s, i) { s.hidden = i !== current; });
    var chapter = shots[current].getAttribute('data-chapter');
    chapters.forEach(function (c) {
      if (c.getAttribute('data-chapter') === chapter) c.setAttribute('aria-current', 'true');
      else c.removeAttribute('aria-current');
    });
    if (count) count.textContent = (current + 1) + ' / ' + shots.length;
  }
  if (shots.length) {
    document.querySelector('.shot-prev').addEventListener('click', function () { showShot(current - 1); });
    document.querySelector('.shot-next').addEventListener('click', function () { showShot(current + 1); });
    chapters.forEach(function (c) {
      c.addEventListener('click', function () {
        for (var i = 0; i < shots.length; i++) {
          if (shots[i].getAttribute('data-chapter') === c.getAttribute('data-chapter')) { showShot(i); return; }
        }
      });
    });
    document.querySelector('.shots').addEventListener('keydown', function (e) {
      if (e.key === 'ArrowRight') showShot(current + 1);
      if (e.key === 'ArrowLeft') showShot(current - 1);
    });
    showShot(0);
  }

  // Lightbox: a screenshot fills the screen, steps like the stepper (which follows along),
  // and a tap zooms it to full height so a landscape shot is readable on a portrait phone.
  var lightbox = document.getElementById('lightbox');
  if (lightbox && shots.length && typeof lightbox.showModal === 'function') {
    var lbImg = lightbox.querySelector('.lb-stage img');
    var lbStage = lightbox.querySelector('.lb-stage');
    var lbCaption = lightbox.querySelector('.lb-caption');
    var lbCount = lightbox.querySelector('.lb-count');
    var lbShow = function (index) {
      showShot(index);
      var img = shots[current].querySelector('img');
      lightbox.classList.remove('zoomed');
      lbImg.src = img.currentSrc || img.src;
      lbCaption.textContent = shots[current].querySelector('figcaption').textContent;
      lbCount.textContent = (current + 1) + ' / ' + shots.length;
    };
    shots.forEach(function (shot, i) {
      var img = shot.querySelector('img');
      img.tabIndex = 0;
      img.setAttribute('role', 'button');
      img.setAttribute('aria-label', 'Enlarge screenshot');
      var open = function () { lbShow(i); lightbox.showModal(); };
      img.addEventListener('click', open);
      img.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); }
      });
    });
    lightbox.querySelector('.lb-prev').addEventListener('click', function () { lbShow(current - 1); });
    lightbox.querySelector('.lb-next').addEventListener('click', function () { lbShow(current + 1); });
    lightbox.querySelector('.lb-close').addEventListener('click', function () { lightbox.close(); });
    lightbox.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowRight') lbShow(current + 1);
      if (e.key === 'ArrowLeft') lbShow(current - 1);
    });
    lbImg.addEventListener('click', function () {
      var zoomed = lightbox.classList.toggle('zoomed');
      // centre the zoomed shot instead of starting at its left edge
      if (zoomed) lbStage.scrollLeft = (lbStage.scrollWidth - lbStage.clientWidth) / 2;
    });
    // A horizontal swipe steps through the shots; while zoomed the same gesture pans instead.
    var touchX = null;
    lbStage.addEventListener('touchstart', function (e) { touchX = e.touches.length === 1 ? e.touches[0].clientX : null; }, { passive: true });
    lbStage.addEventListener('touchend', function (e) {
      if (touchX === null || lightbox.classList.contains('zoomed')) return;
      var dx = e.changedTouches[0].clientX - touchX;
      if (Math.abs(dx) > 50) lbShow(current + (dx < 0 ? 1 : -1));
      touchX = null;
    });
  }

  // Help tooltip: hover and focus work in CSS; this adds tap-to-toggle and Esc to dismiss.
  document.querySelectorAll('.tip').forEach(function (tip) {
    var btn = tip.querySelector('.tip-btn');
    btn.addEventListener('click', function () {
      tip.classList.remove('dismissed');
      tip.classList.toggle('open');
    });
    tip.addEventListener('mouseleave', function () { tip.classList.remove('dismissed'); });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') { tip.classList.remove('open'); tip.classList.add('dismissed'); }
    });
    document.addEventListener('click', function (e) {
      if (!tip.contains(e.target)) tip.classList.remove('open');
    });
  });

  // Light/dark toggle: the system setting applies until a visitor picks one, and that pick is kept in localStorage.
  var themeKey = 'bbh-theme';
  var themeBtn = document.getElementById('theme-toggle');
  var systemDark = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
  function storedTheme() {
    try { var t = localStorage.getItem(themeKey); return t === 'light' || t === 'dark' ? t : null; } catch (e) { return null; }
  }
  function currentTheme() {
    return storedTheme() || (systemDark && systemDark.matches ? 'dark' : 'light');
  }
  function paintTheme() {
    themeBtn.setAttribute('aria-checked', String(currentTheme() === 'dark'));
  }
  if (themeBtn) {
    paintTheme();
    themeBtn.addEventListener('click', function () {
      var next = currentTheme() === 'dark' ? 'light' : 'dark';
      document.documentElement.setAttribute('data-theme', next);
      try { localStorage.setItem(themeKey, next); } catch (e) { /* the choice then lasts for this page only */ }
      paintTheme();
    });
    if (systemDark && systemDark.addEventListener) systemDark.addEventListener('change', paintTheme);
  }

  // Back-to-top button appears once the header has scrolled out of view.
  var toTop = document.getElementById('to-top');
  if (toTop) {
    var syncToTop = function () { toTop.hidden = window.scrollY <= window.innerHeight; };
    toTop.addEventListener('click', function () { window.scrollTo({ top: 0, behavior: 'smooth' }); });
    window.addEventListener('scroll', syncToTop, { passive: true });
    syncToTop();
  }

  // Gesture explainer in a modal; without <dialog> support the link opens the page itself.
  var explainer = document.getElementById('explainer');
  var explainerOpen = document.getElementById('explainer-open');
  if (explainer && explainerOpen && typeof explainer.showModal === 'function') {
    var frame = explainer.querySelector('.explainer-stage iframe');
    // The App-Simulation is responsive: the frame simply fills the stage and the page lays itself
    // out (side by side in the wide desktop modal and on landscape screens, stacked in portrait).
    // The page's own light/dark choice wins over the system setting the simulation would follow.
    var fit = function () {
      if (!explainer.open) return;
      var src = explainerOpen.getAttribute('href') + '?v=app-simulation-2&theme=' + currentTheme();
      if (frame.getAttribute('src') !== src) frame.src = src;
    };
    // The header's Try button and the showcase button both open it.
    document.querySelectorAll('[data-explainer]').forEach(function (trigger) {
      trigger.addEventListener('click', function (e) {
        e.preventDefault();
        explainer.showModal();
        fit();
      });
    });
    explainer.querySelector('.explainer-close').addEventListener('click', function () { explainer.close(); });
    explainer.addEventListener('click', function (e) { if (e.target === explainer) explainer.close(); });
  }

  // Screenshot gallery in a modal like the App-Simulation. Without <dialog> support (or without
  // JavaScript) it stays the inline section the links point to.
  var gallery = document.getElementById('gallery');
  var galleryInline = document.getElementById('screenshots');
  if (gallery && galleryInline && typeof gallery.showModal === 'function') {
    gallery.querySelector('.gallery-stage').appendChild(galleryInline.querySelector('.shots'));
    galleryInline.hidden = true;
    var openGallery = function () { if (!gallery.open) gallery.showModal(); };
    document.querySelectorAll('[data-gallery]').forEach(function (trigger) {
      trigger.addEventListener('click', function (e) { e.preventDefault(); openGallery(); });
    });
    gallery.querySelector('.explainer-close').addEventListener('click', function () { gallery.close(); });
    gallery.addEventListener('click', function (e) { if (e.target === gallery) gallery.close(); });
    if (location.hash === '#screenshots') openGallery();
  } else {
    document.querySelectorAll('[data-gallery]').forEach(function (trigger) {
      trigger.addEventListener('click', function () { galleryInline.open = true; });
    });
  }

  // YouTube loads only after a click, from the cookie-less domain.
  var yt = document.getElementById('yt');
  if (yt) {
    yt.addEventListener('click', function (e) {
      e.preventDefault();
      var f = document.createElement('iframe');
      f.src = 'https://www.youtube-nocookie.com/embed/' + yt.dataset.id + '?autoplay=1&rel=0';
      f.title = 'YouTube video player';
      f.allow = 'autoplay; encrypted-media; picture-in-picture; fullscreen';
      f.allowFullscreen = true;
      yt.textContent = '';
      yt.appendChild(f);
    });
  }

  // Copy the signing certificate digest.
  var copy = document.getElementById('copy');
  if (copy && navigator.clipboard) {
    copy.hidden = false;
    copy.addEventListener('click', function () {
      navigator.clipboard.writeText(document.getElementById('sha').textContent).then(function () {
        copy.textContent = 'Copied'; document.getElementById('copy-status').textContent = 'Certificate hash copied';
        setTimeout(function () { copy.textContent = 'Copy'; document.getElementById('copy-status').textContent = ''; }, 1500);
      });
    });
  }

  // Latest releases: download link, version and changelog, straight from GitHub.
  function inline(parent, text) {
    text.split('**').forEach(function (part, i) {
      if (!part) return;
      if (i % 2) { var s = document.createElement('strong'); s.textContent = part; parent.appendChild(s); }
      else parent.appendChild(document.createTextNode(part));
    });
  }

  function renderNotes(body) {
    var box = document.createDocumentFragment();
    var list = null;
    (body || '').split(/\r?\n/).forEach(function (line) {
      line = line.trim();
      if (!line || /^\*\*Full Changelog\*\*/.test(line)) { list = null; return; }
      var el;
      if (line.indexOf('## ') === 0) {
        list = null; el = document.createElement('h4'); inline(el, line.slice(3)); box.appendChild(el);
      } else if (line.indexOf('- ') === 0) {
        if (!list) { list = document.createElement('ul'); box.appendChild(list); }
        el = document.createElement('li'); inline(el, line.slice(2)); list.appendChild(el);
      } else {
        list = null; el = document.createElement('p'); inline(el, line); box.appendChild(el);
      }
    });
    return box;
  }

  function fmtDate(iso) {
    return new Date(iso).toLocaleDateString('en', { year: 'numeric', month: 'short', day: 'numeric' });
  }

  // A local preview can link site/build/release-notes.md to notes under docs/release-notes.
  if (['localhost', '127.0.0.1', '[::1]'].includes(location.hostname)) {
    fetch('build/release-notes.md', { cache: 'no-store' })
      .then(function (response) { return response.ok ? response.text() : ''; })
      .then(function (body) {
        var title = body.match(/^Release title: (.+)$/m);
        if (!title) return;
        var draft = document.createElement('article');
        draft.className = 'release';
        var heading = document.createElement('h3');
        heading.textContent = title[1].trim();
        draft.appendChild(heading);
        draft.appendChild(renderNotes(body.replace(/^\s*<!--[\s\S]*?-->/, '')));
        document.getElementById('releases').before(draft);
      })
      .catch(function () { /* the published changelog can still load */ });
  }

  fetch('https://api.github.com/repos/' + REPO + '/releases?per_page=100', { headers: { Accept: 'application/vnd.github+json' } })
    .then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
    .then(function (releases) {
      releases = releases.filter(function (r) { return !r.draft && !r.prerelease; });
      if (!releases.length) return;

      var total = 0;
      releases.forEach(function (r) {
        (r.assets || []).forEach(function (a) { if (/\.apk$/.test(a.name)) total += a.download_count || 0; });
      });
      document.getElementById('stat-version').textContent = releases[0].tag_name;
      document.getElementById('stat-version-label').textContent = 'latest release';
      if (total) {
        document.getElementById('stat-downloads').textContent = total.toLocaleString('en');
        document.getElementById('stat-downloads-label').textContent = 'APK downloads on GitHub';
      }
      releases = releases.slice(0, 2);


      var apk = (releases[0].assets || []).filter(function (a) { return /\.apk$/.test(a.name); })[0];
      var dl = document.getElementById('download');
      document.getElementById('dl-version').textContent = releases[0].tag_name +
        (apk ? ' · ' + (apk.size / 1048576).toFixed(1) + ' MB' : '');
      if (apk) dl.href = apk.browser_download_url;

      var host = document.getElementById('releases');
      host.textContent = '';
      releases.forEach(function (rel) {
        var art = document.createElement('article');
        art.className = 'release';
        var h = document.createElement('h3');
        h.appendChild(document.createTextNode(rel.name || rel.tag_name));
        var t = document.createElement('time');
        t.dateTime = rel.published_at;
        t.textContent = fmtDate(rel.published_at);
        h.appendChild(t);
        art.appendChild(h);
        art.appendChild(renderNotes(rel.body));
        host.appendChild(art);
      });
    })
    .catch(function () { /* keep the static fallback */ });
})();
