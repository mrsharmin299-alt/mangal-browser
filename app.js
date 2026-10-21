// Mangal Browser shared home UI + optional Firebase Google authentication.
// Configure web/firebase-config.js with your Firebase project's public web config.
// Firebase client config is not a password; protect the project with authorized domains,
// OAuth settings, security rules, and provider configuration.

const sites=[
 {name:'Google',icon:'google',url:'https://www.google.com'},
 {name:'YouTube',icon:'youtube',url:'https://www.youtube.com'},
 {name:'Facebook',icon:'facebook',url:'https://www.facebook.com'},
 {name:'Wikipedia',icon:'wikipedia',url:'https://www.wikipedia.org'},
 {name:'ChatGPT',icon:'chatgpt',url:'https://chatgpt.com'},
 {name:'Instagram',icon:'instagram',url:'https://www.instagram.com'},
 {name:'Twitter / X',icon:'x',url:'https://x.com'},
 {name:'Bangladesh',icon:'bd',url:'https://bangladesh.gov.bd'},
 {name:'Gmail',icon:'gmail',url:'https://mail.google.com'},
 {name:'যোগ করুন',icon:'add',url:'#add'}
];
const popular=[sites[1],sites[2],sites[3],sites[4],{name:'OpenAI',icon:'openai',url:'https://openai.com'}];
const shortcuts=document.getElementById('shortcuts');
const popularGrid=document.getElementById('popularGrid');
const authModal=document.getElementById('authModal');
const authStatus=document.getElementById('authStatus');
let auth=null, authReady=false;

function toast(msg){const el=document.getElementById('toast');el.textContent=msg;el.style.display='block';clearTimeout(window.toastTimer);window.toastTimer=setTimeout(()=>el.style.display='none',2600)}
function openSite(url){if(url==='#add'){const name=prompt('ওয়েবসাইটের নাম লিখুন');if(!name)return;const address=prompt('ওয়েবসাইটের URL লিখুন (যেমন https://example.com)');if(!address)return;let fixed=/^https?:\/\//i.test(address)?address:'https://'+address;sites.splice(sites.length-1,0,{name,icon:'globe',url:fixed});hydrateIcons();render();return} if(url.startsWith('#')){toast('এই সুবিধাটি পরবর্তী সংস্করণে যোগ হবে।');return} window.location.href=url}
function icon(k){return (window.ICONS&&window.ICONS[k])||''}
function hydrateIcons(root){(root||document).querySelectorAll('[data-icon]').forEach(el=>{if(!el.dataset.done){el.innerHTML=icon(el.dataset.icon);el.dataset.done='1'}})}
function render(){shortcuts.innerHTML='';sites.forEach(s=>{const b=document.createElement('button');b.className='shortcut';b.innerHTML=`<span class="tile">${icon(s.icon)}</span><span>${escapeHtml(s.name)}</span>`;b.addEventListener('click',()=>openSite(s.url));shortcuts.appendChild(b)});popularGrid.innerHTML='';popular.forEach(s=>{const b=document.createElement('button');b.className='popular-item';b.innerHTML=`<span class="popular-icon">${icon(s.icon)}</span><span>${escapeHtml(s.name)}</span>`;b.addEventListener('click',()=>openSite(s.url));popularGrid.appendChild(b)})}
function showAuth(){authModal.classList.add('open');authModal.setAttribute('aria-hidden','false');authStatus.textContent=''}
function hideAuth(){authModal.classList.remove('open');authModal.setAttribute('aria-hidden','true')}
function setAccount(user){const btn=document.getElementById('accountBtn');const signOutBtn=document.getElementById('signOutBtn');if(user){btn.classList.add('signed');btn.innerHTML=`${icon('user')}<span>${escapeHtml((user.displayName||'অ্যাকাউন্ট').split(' ')[0])}</span>`;btn.title=user.email||'';signOutBtn.hidden=false;authStatus.textContent=`সাইন ইন করা হয়েছে: ${user.email||''}`}else{btn.classList.remove('signed');btn.innerHTML=`${icon('user')}<span>লগইন</span>`;btn.title='Google দিয়ে সাইন ইন';signOutBtn.hidden=true}}
function escapeHtml(s){return String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
try{
  const firebaseConfig = window.MANGAL_FIREBASE_CONFIG || {};
  if(window.firebase && firebaseConfig.apiKey && !firebaseConfig.apiKey.startsWith('YOUR_') && firebaseConfig.authDomain && firebaseConfig.projectId){
    if(!firebase.apps.length) firebase.initializeApp(firebaseConfig);
    auth=firebase.auth();authReady=true;
    auth.onAuthStateChanged(user=>setAccount(user));
  }
}catch(err){console.error('Firebase initialization error',err)}
document.getElementById('searchForm').addEventListener('submit',e=>{e.preventDefault();const q=document.getElementById('searchInput').value.trim();if(!q)return;const url=/^(https?:\/\/|localhost|[\w-]+\.[a-z]{2,})/i.test(q)?(q.startsWith('http')?q:'https://'+q):'https://www.google.com/search?q='+encodeURIComponent(q);window.location.href=url});
document.getElementById('voiceBtn').addEventListener('click',()=>{const SR=window.SpeechRecognition||window.webkitSpeechRecognition;if(!SR){toast('এই ডিভাইসে ভয়েস সার্চ সমর্থিত নয়।');return}const r=new SR();r.lang='bn-BD';r.onresult=e=>{document.getElementById('searchInput').value=e.results[0][0].transcript;document.getElementById('searchForm').requestSubmit()};r.onerror=()=>toast('ভয়েস সার্চ চালু করা যায়নি।');r.start()});
document.getElementById('aiBtn').addEventListener('click',()=>openSite('https://chatgpt.com'));
document.getElementById('menuBtn').addEventListener('click',()=>toast('মঙ্গল ব্রাউজার মেনু: সেটিংস, Extension Store ও প্রাইভেসি'));
document.getElementById('showAll').addEventListener('click',()=>toast('আরও জনপ্রিয় সাইট পরবর্তী সংস্করণে যোগ হবে।'));
document.getElementById('learnMore').addEventListener('click',()=>toast('মঙ্গল ব্রাউজার — দ্রুত, নিরাপদ ও স্মার্ট ব্রাউজিং।'));
document.querySelectorAll('.bottom-nav button').forEach(b=>b.addEventListener('click',()=>{document.querySelectorAll('.bottom-nav button').forEach(x=>x.classList.remove('active'));b.classList.add('active');const p=b.dataset.page;if(p==='home')window.scrollTo({top:0,behavior:'smooth'});else toast(({tabs:'ট্যাব ম্যানেজার পরবর্তী সংস্করণে আসছে।',bookmarks:'বুকমার্ক সুবিধা পরবর্তী সংস্করণে আসছে।',downloads:'ডাউনলোড ম্যানেজার পরবর্তী সংস্করণে আসছে।',settings:'সেটিংস ও Extension Store পরবর্তী সংস্করণে আসছে।'})[p]||p)}));
document.getElementById('accountBtn').addEventListener('click',showAuth);
document.getElementById('closeAuth').addEventListener('click',hideAuth);
document.getElementById('continueGuest').addEventListener('click',hideAuth);
authModal.addEventListener('click',e=>{if(e.target===authModal)hideAuth()});
document.getElementById('googleSignIn').addEventListener('click',async()=>{
  if(!authReady){authStatus.textContent='Firebase সেটআপ করা হয়নি। README-এর Google Sign-In কনফিগারেশন ধাপ অনুসরণ করুন।';return}
  try{
    authStatus.textContent='Google সাইন-ইন খোলা হচ্ছে…';
    const provider=new firebase.auth.GoogleAuthProvider();
    provider.setCustomParameters({prompt:'select_account'});
    // Popup auth is for supported web origins only. Native Android and packaged Electron
    // need platform-specific OAuth handling as described in AUTH_SETUP.md.
    const result=await auth.signInWithPopup(provider);
    setAccount(result.user);authStatus.textContent='সাইন-ইন সফল হয়েছে।';setTimeout(hideAuth,700);
  }catch(err){
    console.error(err);
    const msg=err?.code==='auth/unauthorized-domain'?'এই ডোমেইন Firebase Authorized domains-এ যোগ করুন.':err?.code==='auth/popup-blocked'?'পপ-আপ ব্লক হয়েছে। ব্রাউজারে পপ-আপ অনুমতি দিন।':'সাইন-ইন সম্পন্ন হয়নি। Firebase ও OAuth কনফিগারেশন যাচাই করুন।';
    authStatus.textContent=msg;
  }
});
document.getElementById('signOutBtn').addEventListener('click',async()=>{if(!auth)return;try{await auth.signOut();setAccount(null);authStatus.textContent='সাইন আউট সম্পন্ন।'}catch(e){authStatus.textContent='সাইন আউট করা যায়নি।'}});
hydrateIcons();render();
document.querySelector('.bottom-nav [data-page="tabs"]').addEventListener('click',e=>{if(window.MangalApp){e.stopImmediatePropagation();MangalApp.openTabs()}},true);
document.getElementById('menuBtn').addEventListener('click',e=>{if(window.MangalApp){e.stopImmediatePropagation();MangalApp.openMenu()}},true);
