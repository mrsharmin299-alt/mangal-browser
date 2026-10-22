/* Mangal Browser — ফোন কাত করলে নড়া 3D মঙ্গল
   index.html-এর </body>-এর ঠিক আগে যোগ করুন:  <script src="mars3d.js" defer></script>
   সেন্সর না পেলে আঙুলের নড়াচড়া আর নিজে নিজে মৃদু দুলুনি কাজ করবে। */
(function () {
  var stage = document.querySelector('.stage');
  if (!stage) return;

  var reduce = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;

  // ---------- স্টাইল ----------
  var css = [
    '.stage{perspective:900px;transform-style:preserve-3d}',
    '.m3-l{position:absolute;inset:-24px;pointer-events:none;will-change:transform;filter:hue-rotate(var(--m3-hue,0deg))}',
    '.m3-l i{position:absolute;border-radius:50%;background:#ffd9c2;opacity:var(--o,.5);animation:m3tw var(--d,4s) ease-in-out infinite}',
    '@keyframes m3tw{0%,100%{opacity:var(--o,.5)}50%{opacity:.08}}',
    '.planet-3d{will-change:transform;transform-style:preserve-3d;box-shadow:0 0 30px 4px rgba(var(--m3-glow,255,74,31),.4),0 0 90px 10px rgba(var(--m3-glow,255,74,31),.2)}',
    '.planet-3d .tex{filter:hue-rotate(var(--m3-hue,0deg))}',
    /* আলোর দিক ফোনের কাতের সাথে সরে */
    '.planet-3d:before{background:radial-gradient(circle at var(--lx,66%) var(--ly,22%),#ffffff33 0,#ffffff00 22%),radial-gradient(circle at var(--lx,66%) var(--ly,22%),#0000 0,#0000 38%,#1a040866 62%,#0a0204dd 82%,#050102 100%)}',
    '.moon{will-change:transform;z-index:1;filter:hue-rotate(var(--m3-hue,0deg))}',
    '.stars{will-change:transform}',
    '.hero-copy{will-change:transform}',
    '.m3-theme{position:absolute;right:12px;bottom:46px;z-index:6;width:38px;height:38px;border-radius:50%;display:grid;place-items:center;font-size:17px;color:#fff;cursor:pointer;padding:0;',
    'background:linear-gradient(135deg,rgba(255,255,255,.2),rgba(255,255,255,.05));border:1px solid rgba(255,255,255,.28);',
    'box-shadow:0 8px 18px rgba(0,0,0,.5),0 0 14px rgba(var(--m3-glow,255,74,31),.35),inset 0 1px 0 rgba(255,255,255,.4);',
    '-webkit-backdrop-filter:blur(10px);backdrop-filter:blur(10px);transition:transform .15s}',
    '.m3-theme:active{transform:scale(.9) rotateX(12deg)}',
    '.m3-name{position:absolute;right:58px;bottom:54px;z-index:6;font-size:12px;color:#fff;padding:5px 12px;border-radius:14px;',
    'background:rgba(0,0,0,.45);-webkit-backdrop-filter:blur(8px);backdrop-filter:blur(8px);opacity:0;transition:opacity .3s;pointer-events:none}',
    '.m3-name.on{opacity:1}',
    '@media(prefers-reduced-motion:reduce){.m3-l i{animation:none}}'
  ].join('');
  var st = document.createElement('style');
  st.textContent = css;
  document.head.appendChild(st);

  // ---------- উপাদান নিশ্চিত করা ----------
  var oldSvg = stage.querySelector('.planet-svg');
  if (oldSvg) oldSvg.style.display = 'none';

  var planet = stage.querySelector('.planet-3d');
  if (!planet) {
    planet = document.createElement('div');
    planet.className = 'planet-3d';
    planet.innerHTML = '<div class="tex"></div>';
    stage.insertBefore(planet, stage.firstChild);
  }
  var moon = stage.querySelector('.moon');
  if (!moon) {
    moon = document.createElement('div');
    moon.className = 'moon';
    stage.appendChild(moon);
  }
  var hero = stage.querySelector('.hero-copy');
  var oldStars = stage.querySelector('.stars');

  // ---------- তারার দুই স্তর (দূরে ও কাছে) ----------
  function makeLayer(n, minS, maxS, minO, maxO) {
    var l = document.createElement('div');
    l.className = 'm3-l';
    for (var i = 0; i < n; i++) {
      var s = document.createElement('i');
      var size = minS + Math.random() * (maxS - minS);
      s.style.cssText = 'left:' + (Math.random() * 100) + '%;top:' + (Math.random() * 75) + '%;width:' + size + 'px;height:' + size + 'px;' +
        '--o:' + (minO + Math.random() * (maxO - minO)).toFixed(2) + ';--d:' + (3 + Math.random() * 4).toFixed(1) + 's;animation-delay:-' + (Math.random() * 6).toFixed(1) + 's';
      l.appendChild(s);
    }
    stage.insertBefore(l, stage.firstChild);
    return l;
  }
  var farStars = makeLayer(38, 1, 1.8, .25, .6);
  var nearStars = makeLayer(14, 1.8, 3, .4, .9);

  // ---------- থিম ----------
  var THEMES = [
    { n: 'মঙ্গল', hue: 0, glow: '255,74,31', glass: 'rgba(255,41,69,.30)' },
    { n: 'পৃথিবী', hue: 205, glow: '70,150,255', glass: 'rgba(70,150,255,.30)' },
    { n: 'নিয়ন', hue: 110, glow: '60,255,140', glass: 'rgba(60,255,140,.28)' }
  ];
  var ti = 0;
  try { ti = parseInt(localStorage.getItem('m3theme') || '0', 10) || 0; } catch (e) {}
  if (ti < 0 || ti >= THEMES.length) ti = 0;

  var btn = document.createElement('button');
  btn.className = 'm3-theme';
  btn.setAttribute('aria-label', 'থিম বদলান');
  btn.textContent = '🎨';
  var label = document.createElement('div');
  label.className = 'm3-name';
  stage.appendChild(btn);
  stage.appendChild(label);

  var labelTimer;
  function applyTheme(show) {
    var t = THEMES[ti];
    stage.style.setProperty('--m3-hue', t.hue + 'deg');
    stage.style.setProperty('--m3-glow', t.glow);
    document.documentElement.style.setProperty('--glow', t.glass);
    if (show) {
      label.textContent = t.n;
      label.classList.add('on');
      clearTimeout(labelTimer);
      labelTimer = setTimeout(function () { label.classList.remove('on'); }, 1400);
    }
  }
  btn.addEventListener('click', function () {
    ti = (ti + 1) % THEMES.length;
    try { localStorage.setItem('m3theme', String(ti)); } catch (e) {}
    applyTheme(true);
  });
  applyTheme(false);

  // ---------- নড়াচড়া (প্যারালাক্স) ----------
  var tx = 0, ty = 0, x = 0, y = 0, lastInput = 0;
  var b0 = null, g0 = null;
  function clamp(v) { return Math.max(-1, Math.min(1, v)); }

  if (!reduce) {
    // ফোনের সেন্সর
    window.addEventListener('deviceorientation', function (e) {
      if (e.beta == null || e.gamma == null) return;
      if (b0 === null) { b0 = e.beta; g0 = e.gamma; }
      // ধীরে ধীরে নতুন ধরার ভঙ্গিতে কেন্দ্র ঠিক করে
      b0 += (e.beta - b0) * 0.004;
      g0 += (e.gamma - g0) * 0.004;
      tx = clamp((e.gamma - g0) / 22);
      ty = clamp((e.beta - b0) / 22);
      lastInput = Date.now();
    }, true);

    // আঙুল / মাউস
    function pointer(cx, cy) {
      var r = stage.getBoundingClientRect();
      tx = clamp(((cx - r.left) / r.width - 0.5) * 2);
      ty = clamp(((cy - r.top) / r.height - 0.5) * 2);
      lastInput = Date.now();
    }
    stage.addEventListener('touchmove', function (e) {
      var t = e.touches[0];
      if (t) pointer(t.clientX, t.clientY);
    }, { passive: true });
    stage.addEventListener('pointermove', function (e) { pointer(e.clientX, e.clientY); }, { passive: true });
  }

  function apply(px, py) {
    var f = farStars, n = nearStars;
    f.style.transform = 'translate3d(' + (px * -6) + 'px,' + (py * -5) + 'px,0)';
    n.style.transform = 'translate3d(' + (px * -15) + 'px,' + (py * -12) + 'px,0)';
    if (oldStars) oldStars.style.transform = 'translate3d(' + (px * -9) + 'px,' + (py * -7) + 'px,0)';
    planet.style.transform = 'translate3d(' + (px * -11) + 'px,' + (py * -8) + 'px,0) rotateX(' + (py * -5) + 'deg) rotateY(' + (px * 7) + 'deg)';
    planet.style.setProperty('--lx', (66 + px * 20) + '%');
    planet.style.setProperty('--ly', (22 + py * 16) + '%');
    moon.style.transform = 'translate3d(' + (px * 24) + 'px,' + (py * 16) + 'px,0)';
    if (hero) hero.style.transform = 'translate3d(' + (px * -4) + 'px,' + (py * -3) + 'px,0)';
  }

  var raf;
  function loop(t) {
    var idle = Date.now() - lastInput > 2500;
    var gx = tx, gy = ty;
    if (idle && !reduce) {
      gx = Math.sin(t / 3600) * 0.35;
      gy = Math.cos(t / 4300) * 0.25;
    }
    x += (gx - x) * 0.08;
    y += (gy - y) * 0.08;
    apply(x, y);
    raf = requestAnimationFrame(loop);
  }

  function start() { if (!raf) raf = requestAnimationFrame(loop); }
  function stop() { if (raf) { cancelAnimationFrame(raf); raf = 0; } }
  document.addEventListener('visibilitychange', function () { document.hidden ? stop() : start(); });
  if (reduce) apply(0, 0); else start();
})();
