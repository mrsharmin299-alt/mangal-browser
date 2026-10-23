// উইকিপিডিয়ায় ঢুকলে পেজের উপরে একটা বার্তা দেখায়
var bar = document.createElement('div');
bar.textContent = '🚀 মঙ্গল ব্রাউজারের এক্সটেনশন চালু আছে';
bar.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:99999;padding:8px;text-align:center;background:#ff2945;color:#fff;font:14px sans-serif';
document.documentElement.appendChild(bar);
setTimeout(function () { bar.remove(); }, 3000);
