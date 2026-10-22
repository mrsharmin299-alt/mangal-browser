/* Mangal Browser — হোম পেজ ↔ নেটিভ অ্যাপ সেতু */
(function () {
  function go(u) { location.href = u; }

  // [data-go] বাটন: AI Mode, Incognito
  document.addEventListener('click', function (e) {
    var t = e.target.closest ? e.target.closest('[data-go]') : null;
    if (t) { e.preventDefault(); go(t.getAttribute('data-go')); }
  });

  // নিচের নেভ: ট্যাব, বুকমার্ক ও ডাউনলোড নেটিভ স্ক্রিন খোলে
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
})();
