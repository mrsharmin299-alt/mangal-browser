/* Mangal Browser — হোম পেজ ↔ নেটিভ অ্যাপ সেতু
   index.html-এ আগেই যোগ করা আছে: <script src="bridge.js" defer></script>
   mangal://tabs | bookmarks | history | downloads | incognito | newtab */
(function () {
  function go(u) { location.href = u; }

  // [data-go] বাটন: AI Mode, Incognito ইত্যাদি
  document.addEventListener('click', function (e) {
    var t = e.target.closest ? e.target.closest('[data-go]') : null;
    if (t) { e.preventDefault(); go(t.getAttribute('data-go')); }
  });

  // নিচের নেভ: ট্যাব, বুকমার্ক ও ডাউনলোড এখন নেটিভ স্ক্রিন খোলে (পুরোনো app.js-এর আগে ধরে)
  var MAP = { tabs: 'mangal://tabs', bookmarks: 'mangal://bookmarks', downloads: 'mangal://downloads' };
  document.addEventListener('click', function (e) {
    var b = e.target.closest ? e.target.closest('.bottom-nav button[data-page]') : null;
    if (!b) return;
    var u = MAP[b.getAttribute('data-page')];
    if (u) {
      e.stopImmediatePropagation();
      e.preventDefault();
      go(u);
    }
  }, true);

  // 🛡 আজকের ব্লক কাউন্টার: নেটিভ অ্যাপ URL-এর # অংশে পাঠায় (#b=আজ&t=মোট)
  function bn(n) { return String(n).replace(/\d/g, function (d) { return '০১২৩৪৫৬৭৮৯'.charAt(d); }); }
  var m = /b=(\d+)&t=(\d+)/.exec(location.hash || '');
  var today = m ? parseInt(m[1], 10) : 0;
  var total = m ? parseInt(m[2], 10) : 0;
  var host = document.querySelector('.chips') || document.getElementById('searchForm');
  if (host && host.parentNode) {
    var card = document.createElement('div');
    card.className = 'shield-card';
    card.innerHTML = '<div class="sh-ic">🛡</div><div class="sh-tx"><b id="shNum">' + bn(0) + '</b> টি বিজ্ঞাপন ও ট্র্যাকার আজ আটকানো হয়েছে' +
      '<small>মোট ' + bn(total) + ' টি • আপনার ব্রাউজিং আরও দ্রুত ও নিরাপদ</small></div>';
    host.parentNode.insertBefore(card, host.nextSibling);
    // সংখ্যা ০ থেকে গুনে উঠবে
    var num = card.querySelector('#shNum');
    var t0 = null;
    function tick(ts) {
      if (t0 === null) t0 = ts;
      var p = Math.min(1, (ts - t0) / 900);
      num.textContent = bn(Math.round(today * (1 - Math.pow(1 - p, 3))));
      if (p < 1) requestAnimationFrame(tick);
    }
    if (today > 0) requestAnimationFrame(tick); else num.textContent = bn(0);
  }
})();
