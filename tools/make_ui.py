"""Erzeugt app/src/main/assets/www/index.html aus der Vorschau (mail-vorschau.html).

Ersetzt die Beispieldaten durch die Verbindung zur Android-App (MailBridge).
Aufruf: python3 tools/make_ui.py <vorschau.html> <ziel.html>
"""
import re, sys

src, dst = sys.argv[1], sys.argv[2]
h = open(src, encoding='utf-8').read()

def rep(a, b, n=1):
    global h
    c = h.count(a)
    assert c == n, (c, a[:100])
    h = h.replace(a, b)

def rx(pattern, repl, flags=re.S):
    global h
    h, n = re.subn(pattern, lambda m: repl, h, count=1, flags=flags)
    assert n == 1, pattern[:80]

# 1) Beispieldaten und Vorschau-Hinweise entfernen
rx(r'<script>/\* ===== Vorschau: Beispieldaten.*?</script>\n', '')
rx(r'\.p-side \.pane-head h1::after\{content:"Vorschau".*?\}\n', '')
rx(r"setTimeout\(\(\)=>toast\('Vorschau mit Beispielmails.*?\);\n", '')

# 2) Schnittstelle: statt Server-Aufruf die App-Brücke
rx(r"async function api\(action,data=\{\}\)\{.*?\n  return j;\n\}\n", r'''const __cb={};let __n=0;
window.__reply=(id,ok,payload)=>{const c=__cb[id];if(!c)return;delete __cb[id];let j={};try{j=JSON.parse(payload)}catch(e){}ok?c.res(j):c.rej(new Error(j.error||'Fehler'))};
function api(action,data={}){return new Promise((res,rej)=>{const id='c'+(++__n);__cb[id]={res,rej};try{MailBridge.call(id,JSON.stringify({action,...data}))}catch(e){delete __cb[id];rej(new Error('Die Verbindung zur App fehlt.'))}})}
''')

# 3) Sperre mit echtem Fingerabdruck
rx(r"function showLock\(\)\{.*?\n\}\nasync function start\(\)\{\n", r'''function showLock(){
  $('root').innerHTML=`<div class="login lock"><div class="modal">${LOGO}<h3>Mail</h3><p class="hint">Mit Fingerabdruck entsperren</p>
    <button class="fp" id="fp" aria-label="Mit Fingerabdruck entsperren">${FP}</button>
    <p class="hint small">Tippe auf das Symbol, falls die Abfrage nicht von selbst erscheint.</p></div></div>`;
  $('fp').onclick=()=>MailBridge.unlock();setTimeout(()=>{try{MailBridge.unlock()}catch(e){}},250);
}
window.__unlocked=()=>{const f=$('fp');if(f)f.classList.add('ok');setTimeout(async()=>{UNLOCKED=true;await start();if(window.__pendingOpen){NOTIF=window.__pendingOpen;window.__pendingOpen=null;openFromNotif()}},250)};
window.__relock=()=>{UNLOCKED=false;closeModal();closeNotif();showLock()};
async function start(){
  if(!CFG.__loaded){try{CFG=await api('config');CFG.__loaded=true}catch(e){}}
''')
rep("  try{CFG=await api('config')}catch(e){return}\n", "  try{CFG=await api('config');CFG.__loaded=true}catch(e){return}\n  try{OUTBOX=(await api('outbox')).items||[];if(OUTBOX.length)obTick()}catch(e){}\n")

# 4) Liste mit Textvorschau
rep("q:Q,limit:V.type==='uni'?40:80});", "q:Q,limit:V.type==='uni'?40:80,preview:VIEW==='preview'});")

# 5) KI über den eigenen Schlüssel
rep("if(window.claude&&claude.use)claude.use('sample').then(s=>{AI=s}).catch(()=>{});",
    r"""AI=Object.assign(async(prompt,opts={})=>{const r=await api('ai',{prompt,tier:opts.modelTier==='quick'?'quick':'default'});if(opts.onText)try{opts.onText({text:r.text,delta:r.text})}catch(_){}return {text:r.text,truncated:false}},
  {json:async(prompt,opts={})=>{const r=await api('ai',{prompt:prompt+'\n\nAntworte ausschließlich mit gültigem JSON, ohne weiteren Text.',tier:opts.modelTier==='quick'?'quick':'default'});const t=String(r.text||'').trim();const s=t.search(/[\[{]/);const e=Math.max(t.lastIndexOf('}'),t.lastIndexOf(']'));return JSON.parse(t.slice(s,e+1))}});""")
rep("  if(!AI){toast('Die KI ist in dieser Ansicht nicht verfügbar. In der Android-App läuft sie über deinen eigenen Zugang.');return false}return true}",
    "  if(!CFG.hasAiKey){toast('Bitte zuerst unter Einstellungen → KI-Assistent deinen KI-Schlüssel eintragen.');return false}return true}")
rep("function aiErr(e){", "function aiErr(e){if(e&&e.message&&!e.code)return e.message;")
rx(r"  if\(sec==='ai'\)\{h=.*?\n  if\(sec==='contacts'\)", r'''  if(sec==='ai'){h=`<div class="sgroup">${swH('ai-on',s.ai!==false,'KI-Assistent verwenden','Korrektur lesen, besser formulieren, Mails aus Stichworten entwerfen, Mails zusammenfassen und Antworten vorschlagen.')}</div>
      <div class="sgroup"><div class="field"><label for="ai-key">KI-Schlüssel (Anthropic API)</label><input id="ai-key" type="password" autocomplete="off" placeholder="${CFG.hasAiKey?'Schlüssel ist gespeichert':'sk-ant-…'}"></div>
      <div class="actions"><button class="btn primary" id="ai-save">Schlüssel speichern</button></div>
      <p class="hint">Den Schlüssel bekommst du auf console.anthropic.com unter „API Keys“. Dort lädst du ein kleines Guthaben auf und kannst ein Ausgabenlimit festlegen. Kosten entstehen nur, wenn du eine KI-Funktion antippst.</p></div>
      <p class="hint">Für jede KI-Funktion wird nur der Text geschickt, den du gerade bearbeitest oder liest – nie dein ganzes Postfach.</p>`}
  if(sec==='contacts')''')
rep("  onSw('ai-on',v=>setAndRefresh({ai:v},v?'KI-Assistent an':'KI-Assistent aus'));",
    "  onSw('ai-on',v=>setAndRefresh({ai:v},v?'KI-Assistent an':'KI-Assistent aus'));\n  if($('ai-save'))$('ai-save').onclick=async()=>{const k=$('ai-key').value.trim();if(!k){toast('Bitte den Schlüssel einfügen.');return}try{const r=await api('saveAiKey',{key:k});CFG.hasAiKey=r.hasAiKey;$('ai-key').value='';$('ai-key').placeholder='Schlüssel ist gespeichert';toast('KI-Schlüssel gespeichert')}catch(e){toast(e.message)}};")

# 6) Postausgang über die App (sendet auch, wenn Mail geschlossen ist)
rx(r"function queueSend\(payload,delay,pre\)\{.*?\n  obTimer=setTimeout\(obTick,1000\)\}\n", r'''async function queueSend(payload,delay,pre){try{const r=await api('queue',{payload,delay});OUTBOX.push({id:r.id,payload,pre,sendAt:r.sendAt});renderSide();
  toastAct(`Wird in ${fmtDelay(delay)} gesendet`,'Zurückholen',()=>recall(r.id),8000);obTick()}catch(e){toast(e.message)}}
async function recall(id){const o=OUTBOX.find(x=>x.id===id)||{};OUTBOX=OUTBOX.filter(x=>x.id!==id);renderSide();if(V.type==='outbox')renderList();
  try{const r=await api('recall',{id});const p=r.payload;compose({...(o.pre||{}),title:'Zurückgeholt',acc:p.acc,to:p.to,cc:p.cc,bcc:p.bcc,subject:p.subject});const ed=$('c-body');if(ed)ed.innerHTML=p.html;toast('Mail zurückgeholt. Du kannst sie jetzt ändern.')}catch(e){toast(e.message)}}
async function sendNow(id){OUTBOX=OUTBOX.filter(x=>x.id!==id);renderSide();if(V.type==='outbox')renderList();try{await api('sendNow',{id});toast('Gesendet');loadOverviews()}catch(e){toast(e.message)}}
let obTimer=null;
function obTick(){clearTimeout(obTimer);if(!OUTBOX.length){if(V.type==='outbox')renderList();return}
  const now=Date.now();if(OUTBOX.some(o=>o.sendAt<=now)){OUTBOX=OUTBOX.filter(o=>o.sendAt>now);renderSide();if(V.type==='outbox')renderList();setTimeout(loadOverviews,5000)}
  document.querySelectorAll('[data-cd]').forEach(s=>{const o=OUTBOX.find(x=>x.id===s.dataset.cd);if(o){const r=Math.max(0,Math.round((o.sendAt-now)/1000));s.textContent=`${Math.floor(r/60)}:${String(r%60).padStart(2,'0')}`}});
  obTimer=setTimeout(obTick,1000)}
''')

# 7) Kopieren, Links, Anhänge, Abmelden über die App
rx(r"function copyCode\(btn\)\{.*?catch\(e\)\{selectCode\(\)\}\}\n", "function copyCode(btn){const code=btn.dataset.code;try{MailBridge.copy(code)}catch(e){}btn.innerHTML=`${ic('check')}Kopiert`;toast('Code '+code+' kopiert')}\n")
rep("  const go=()=>{const a=document.createElement('a');a.href=href;a.target='_blank';a.rel='noopener noreferrer';document.body.appendChild(a);a.click();a.remove()};",
    "  const go=()=>{try{MailBridge.openUrl(href)}catch(e){}};")
rx(r"function openAttachment\(i\)\{.*?\n\}\nfunction cleanLinks", r'''function openAttachment(i){
  const d=CUR&&CUR.d,m=CUR&&CUR.m;if(!d)return;const t=(d.attachments||[]).find(x=>x.i===i);if(!t)return;
  const danger=protLevel()>=2&&DANGER_RX.test(t.filename);
  modal(`<h3>${esc(t.filename)}</h3>
    ${danger?`<div class="warnbox">${ic('block')}<div><b>Gefährlicher Dateityp</b><br>Dateien wie diese können Schadprogramme enthalten. Öffne sie nur, wenn du den Absender kennst und die Datei erwartet hast.</div></div>`:''}
    <p class="hint">${fmtSize(t.size)} · Android fragt beim Öffnen, mit welcher App die Datei angezeigt werden soll.</p>
    <div class="menu-list"><button class="btn primary" id="at-open">${ic('file')}Öffnen mit …</button><button class="btn" id="at-save">${ic('down')}In Downloads speichern</button></div>
    <div class="actions" style="margin-top:12px"><button class="btn" data-act="close">Abbrechen</button></div>`,'sheet');
  const run=async mode=>{closeModal();toast(mode==='save'?'Wird gespeichert …':'Wird geladen …');try{await api('attachment',{acc:m.acc,path:m.path,uid:m.uid,i,mode});if(mode==='save')toast('In „Downloads“ gespeichert')}catch(e){toast(e.message)}};
  $('at-open').onclick=()=>run('open');$('at-save').onclick=()=>run('save');
}
function cleanLinks''')
rep("  $('un-go').onclick=async()=>{const tr=$('un-trash').checked,bl=$('un-block').checked;closeModal();",
    "  $('un-go').onclick=async()=>{const tr=$('un-trash').checked,bl=$('un-block').checked;closeModal();\n    try{const d=(CUR&&CUR.m===m)?CUR.d:await api('read',{acc:m.acc,path:m.path,uid:m.uid});const u=d.unsubscribe||'';if(/^https:/.test(u))MailBridge.openUrl(u);else if(/^mailto:/.test(u)){await api('send',{acc:m.acc,to:decodeURIComponent(u.slice(7).split('?')[0]),subject:'unsubscribe',text:'unsubscribe'})}}catch(e){}")
rep("In der App fragt Android einmal, ob Mail deine Kontakte lesen darf. In dieser Vorschau siehst du Beispielkontakte.", "Wähle aus, welche Kontakte mit E-Mail-Adresse ins Adressbuch kommen sollen.")

# 8) Speicher, Benachrichtigungen, Akku
rep("CACHE_MB=48.3", "CACHE_MB=0")
rep("    case 'setsec':SETSEC=t.dataset.s;renderSettingsList();renderRead();setPane('read');break;",
    "    case 'setsec':SETSEC=t.dataset.s;renderSettingsList();renderRead();setPane('read');if(SETSEC==='storage')api('cacheSize').then(r=>{CACHE_MB=r.mb;renderRead();renderSettingsList()}).catch(()=>{});break;")
rep("$('cc-go').onclick=()=>{CACHE_MB=0;", "$('cc-go').onclick=()=>{api('clearCache').catch(()=>{});CACHE_MB=0;")
rep("Sonst schickt HyperOS die App schlafen.</p>`}", "Sonst schickt HyperOS die App schlafen.</p><button class=\"btn fitbtn\" id=\"bat-open\">${ic('gear')}App-Einstellungen öffnen</button>`}")
rep("  ['instant','popup','code','preview','sound'].forEach(k=>onSw('n-'+k,v=>setAndRefresh({notify:{...(S_().notify||{}),[k]:v}})));",
    "  ['instant','popup','code','preview','sound'].forEach(k=>onSw('n-'+k,v=>{setAndRefresh({notify:{...(S_().notify||{}),[k]:v}});if(k==='popup'&&v)api('ensureOverlay').catch(()=>{})}));\n  if($('bat-open'))$('bat-open').onclick=()=>api('openBattery').catch(()=>{});")

# 9) Tastatur: Android schiebt die Ansicht selbst hoch
rep("const coarse=matchMedia('(pointer:coarse)').matches;", "const coarse=false;")

# 10) Rückruf-Funktionen für die App (Zurück-Taste, neue Mails, Öffnen aus Benachrichtigung)
rep("start();\n</script>", r'''window.__back=()=>{const p=document.querySelector('.pop-back');if(p){p.remove();return true}
  if(document.querySelector('.notif')){closeNotif();return true}
  if($('layer')&&$('layer').innerHTML){closeModal();return true}
  const a=$('app');if(!a)return false;
  if(innerWidth<=880&&(a.dataset.pane==='read'||a.dataset.pane==='side')){setPane('list');return true}
  if(V.type!=='uni'||V.role!=='inbox'){V={type:'uni',role:'inbox'};Q='';SEL=null;CUR=null;renderSide();renderRead();loadList();return true}
  return false};
window.__newMail=n=>{notifyPopup(n);loadOverviews();if(V.type==='uni'||V.type==='box')loadList(true)};
window.__openMail=async n=>{NOTIF=n;if(!UNLOCKED){window.__pendingOpen=n;return}await openFromNotif()};
window.__compose=to=>{if(UNLOCKED)compose({to:to+', '})};
start();
</script>''')

# Vollständiges Dokument
assert 'Vorschau mit Beispiel' not in h
page = '<!doctype html><html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><style>:root{color-scheme:light}body{margin:0;font:14px system-ui,sans-serif}img{max-width:100%}[hidden]{display:none!important}</style></head><body>' + h + '</body></html>'
open(dst, 'w', encoding='utf-8').write(page)
print('UI ok', len(page))
