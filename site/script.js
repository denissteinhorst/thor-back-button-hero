// Everything here is optional: the page is complete without JavaScript.
(function () {
  'use strict';

  var REPO = 'denissteinhorst/thor-back-button-hero';

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

  // Gesture explainer in a modal; without <dialog> support the link opens the page itself.
  var explainer = document.getElementById('explainer');
  var explainerOpen = document.getElementById('explainer-open');
  if (explainer && explainerOpen && typeof explainer.showModal === 'function') {
    var stage = explainer.querySelector('.explainer-stage');
    var frame = stage.querySelector('iframe');
    // The explainer is laid out at a fixed 640×640, so it is scaled rather than reflowed.
    var fit = function () {
      stage.style.setProperty('--s', Math.min(1, stage.clientWidth / 640, stage.clientHeight / 640));
    };
    if (window.ResizeObserver) new ResizeObserver(fit).observe(stage);
    else window.addEventListener('resize', fit);
    explainerOpen.addEventListener('click', function (e) {
      e.preventDefault();
      if (!frame.src) frame.src = explainerOpen.getAttribute('href');
      explainer.showModal();
      fit();
    });
    explainer.querySelector('.explainer-close').addEventListener('click', function () { explainer.close(); });
    explainer.addEventListener('click', function (e) { if (e.target === explainer) explainer.close(); });
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
