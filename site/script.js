// SPDX-License-Identifier: 0BSD
import {MODES} from './hotkey-layer-state.js';
const playground=document.querySelector('.playground');
const layerComponent=playground.querySelector('thor-hotkey-layers');
function selectMode(mode){
  const {title,detail}=MODES[mode];
  document.getElementById('scene-title').textContent=title;
  document.getElementById('scene-description').textContent=detail;
  for(const button of playground.querySelectorAll('[data-scene]'))button.setAttribute('aria-pressed',String(button.dataset.scene===mode));
  playground.dataset.mode=mode;
  layerComponent.setAttribute('mode',mode);
  playground.querySelector('.fallback-pane.back span').textContent=mode==='shared'?'Shared hotkeys':'Back hotkeys';
}
for(const button of playground.querySelectorAll('[data-scene]'))button.addEventListener('click',()=>selectMode(button.dataset.scene));
playground.querySelector('.scene-picker').hidden=false;
const modelObserver=new IntersectionObserver(entries=>{
  if(!entries.some(entry=>entry.isIntersecting))return;
  modelObserver.disconnect();
  import('./hotkey-layers.js').catch(error=>console.warn('Using device illustration:',error.message));
},{rootMargin:'180px'});
modelObserver.observe(layerComponent);

const releaseURL='https://api.github.com/repos/denissteinhorst/thor-back-button-hero/releases?per_page=100';
let downloadURL='https://github.com/denissteinhorst/thor-back-button-hero/releases/latest';
let version='';
fetch(releaseURL,{headers:{Accept:'application/vnd.github+json'}}).then(response=>{
  if(!response.ok)throw new Error(response.status);return response.json();
}).then(releases=>{
  releases=releases.filter(release=>!release.draft&&!release.prerelease);
  if(!releases.length)return;
  version=releases[0].tag_name;
  document.querySelector('[data-stat-version]').textContent=version;
  document.querySelector('[data-stat-version-label]').textContent='latest release';
  const total=releases.reduce((sum,release)=>sum+(release.assets||[]).filter(asset=>/\.apk$/i.test(asset.name)).reduce((n,asset)=>n+(asset.download_count||0),0),0);
  if(total){document.querySelector('[data-stat-downloads]').textContent=total.toLocaleString('en');document.querySelector('[data-stat-downloads-label]').textContent='APK downloads on GitHub';}
  const apk=(releases[0].assets||[]).find(asset=>/\.apk$/i.test(asset.name));
  if(apk){const url=new URL(apk.browser_download_url);if(url.protocol==='https:'&&url.hostname==='github.com')downloadURL=url.href;}
  for(const link of document.querySelectorAll('[data-apk]')){link.href=downloadURL;link.textContent=`Download ${version} APK`;}
}).catch(()=>{/* Preserve the original facts and latest-release link when offline. */});

// The static guide is the shared source for dialogs and the no-JavaScript fallback.
let sourcePromise;
function sourceDocument(){
  if(!sourcePromise)sourcePromise=fetch('guide.html').then(response=>{
    if(!response.ok)throw new Error(response.status);return response.text();
  }).then(text=>new DOMParser().parseFromString(text,'text/html')).catch(error=>{sourcePromise=null;throw error;});
  return sourcePromise;
}
function element(tag,className,text){const node=document.createElement(tag);if(className)node.className=className;if(text!==undefined)node.textContent=text;return node;}
function paragraph(text,className='intro'){return element('p',className,text);}
function routeLink(text,route){const a=element('a','library-simulation',text);a.href=`#${route}`;a.dataset.route=route;return a;}
function copyContent(source){
  const clone=document.importNode(source,true);
  for(const unwanted of clone.querySelectorAll('script,iframe,object,embed,button,input,form,img'))unwanted.remove();
  for(const node of [clone,...clone.querySelectorAll('*')]){
    for(const attribute of [...node.attributes])if(attribute.name==='id'||attribute.name==='style'||attribute.name.startsWith('on'))node.removeAttribute(attribute.name);
    for(const attr of ['href','src'])if(node.hasAttribute(attr)){
      const url=new URL(node.getAttribute(attr),new URL('guide.html',location.href));
      if(!['http:','https:'].includes(url.protocol)){node.removeAttribute(attr);continue;}
      node.setAttribute(attr,url.href);
      const routesByHash={'#frontends':'compatibility','#faq':'faq','#install':'setup','#actions':'actions','#ai':'about','#screenshots':'screenshots','#changelog':'updates'};
      if(url.origin===location.origin&&routesByHash[url.hash])node.dataset.route=routesByHash[url.hash];
    }
  }
  return clone;
}
const normalize=text=>text.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]+/g,' ').trim();
const wordsMatch=(haystack,query)=>normalize(query).split(/\s+/).filter(Boolean).every(word=>haystack.includes(word));
const synonyms={screenshot:'picture photo capture screen grab screenshots',record:'video videos gameplay recording commentary voice microphone push to talk',microphone:'voice commentary speech mic',volume:'sound audio louder quieter mute',brightness:'brighter darker light dim',macro:'macros sequence combo replay buttons repeat',gyro:'motion aiming tilt turn turning',fan:'cooling cool quiet speed',last:'previous alt tab recent',power:'sleep standby turn off',lighting:'led lights rgb'};
function keywords(text){return Object.entries(synonyms).filter(([word])=>normalize(text).includes(word)).map(([,aliases])=>aliases).join(' ');}
function searchControls(label,placeholder){
  const group=element('div','search-controls');
  const box=element('div');const text=element('label','',label);text.htmlFor='detail-search';
  const input=element('input');input.id='detail-search';input.type='search';input.placeholder=placeholder;input.autocomplete='off';
  box.append(text,input);group.append(box);return {group,input};
}
const topicFor=title=>/Recording/.test(title)?'capture':/Macros/.test(title)?'play':/Android/.test(title)?'android':/Thor controls/.test(title)?'controls':'screens';
let actionQuery='',actionTopic='all',faqQuery='';
function renderActions(source,topic){
  const intro=paragraph('Find something you want to do. Choose a shortcut for it in the app.');
  const {group,input}=searchControls('Find an action','Try screenshot, voice, fan or macros');
  const filterBox=element('div');const label=element('label','','Topic');label.htmlFor='action-topic';
  const select=element('select');select.id='action-topic';
  for(const [value,name] of [['all','All actions'],['screens','Screens & apps'],['capture','Capture & media'],['play','Macros & gyro'],['controls','Play & Thor controls'],['android','Android']]){
    const option=element('option','',name);option.value=value;select.append(option);
  }
  select.value=topic;input.value=actionQuery;filterBox.append(label,select);group.append(filterBox);
  const count=element('p','search-count');count.setAttribute('role','status');
  const list=element('div');
  const groups=[];
  for(const card of source.querySelectorAll('#action-catalog .action-card')){
    const title=card.querySelector('h4').textContent;
    const wrapper=element('section','library-group');wrapper.append(element('h3','',title));
    const items=[];
    for(const li of card.querySelectorAll('li')){
      const name=li.querySelector('strong');
      if(!name){wrapper.append(paragraph(li.textContent,'library-note'));continue;}
      const description=li.textContent.slice(name.textContent.length).trim();
      const row=element('article','library-item');row.append(element('h4','',name.textContent.replace(/\.$/,'')),paragraph(description,''));
      const searchText=normalize(`${li.textContent} ${keywords(name.textContent)}`);
      const itemTopic=/screenshot/i.test(name.textContent)?'capture':topicFor(title);
      items.push({row,searchText,topic:itemTopic});wrapper.append(row);
    }
    groups.push({wrapper,items,topic:topicFor(title)});list.append(wrapper);
  }
  const noResults=paragraph('No matching action. Try another word, or search the FAQ for setup questions.');noResults.hidden=true;
  const faqLink=routeLink('Search the FAQ','faq');noResults.append(document.createElement('br'),faqLink);
  const update=()=>{
    actionQuery=input.value;actionTopic=select.value;
    let matches=0;
    for(const group of groups){let visible=0;for(const item of group.items){const inTopic=select.value==='all'||select.value===item.topic||(select.value==='controls'&&item.topic==='play');item.row.hidden=!inTopic||!wordsMatch(item.searchText,input.value);if(!item.row.hidden)visible++;}group.wrapper.hidden=!visible;matches+=visible;}
    count.textContent=`${matches} ${matches===1?'result':'results'}`;noResults.hidden=matches>0;
  };
  input.addEventListener('input',update);select.addEventListener('change',update);update();
  body.replaceChildren(intro,group,count,list,noResults,routeLink('Try the actions in the app simulation','simulation'));
}
function renderFAQ(source){
  const {group,input}=searchControls('Find an answer','Try recording, backup or emulator');
  input.value=faqQuery;
  const count=element('p','search-count');count.setAttribute('role','status');
  const list=element('div');const groups=[];let current;
  for(const node of source.querySelector('.faq-list').children){
    if(node.tagName==='H3'){current={wrapper:element('section','faq-group'),items:[]};current.wrapper.append(element('h3','',node.textContent));groups.push(current);list.append(current.wrapper);}
    if(node.tagName==='DETAILS'){
      if(!current){current={wrapper:element('section','faq-group'),items:[]};groups.push(current);list.append(current.wrapper);}
      const row=copyContent(node);row.open=false;current.items.push({row,searchText:normalize(node.textContent)});current.wrapper.append(row);
    }
  }
  const update=()=>{let total=0;for(const group of groups){let visible=0;for(const item of group.items){item.row.hidden=!wordsMatch(item.searchText,input.value);if(!item.row.hidden)visible++;}group.wrapper.hidden=!visible;total+=visible;}count.textContent=total?`${total} ${total===1?'answer':'answers'}`:'No answers found. Try another word.';};
  input.addEventListener('input',()=>{faqQuery=input.value;update();});update();body.replaceChildren(group,count,list);
}
function renderSetup(source){
  const steps=copyContent(source.querySelector('#install .steps'));steps.className='setup-steps';
  const download=element('a','button',version?`Download ${version} APK`:'Download the latest APK');download.href=downloadURL;download.dataset.apk='';
  const note=paragraph('Open the APK on your Thor, enable the accessibility service and choose suggested shortcuts or your own setup. Updating keeps the settings you customized.','setup-note');
  const certificate=element('details','certificate');certificate.append(element('summary','','Verify your download'),paragraph('The signing certificate SHA-256 should match:'),element('code','',source.querySelector('#sha').textContent));
  body.replaceChildren(paragraph('Free for every AYN Thor model. Hand-tested on a Thor running Android 13.'),steps,download,note,certificate,routeLink('Questions about setup?','faq'));
}
function renderScreenshots(source){
  const shots=[...source.querySelectorAll('.shot')].map(shot=>({
    chapter:shot.dataset.chapter,
    src:new URL(shot.querySelector('img').getAttribute('src'),new URL('guide.html',location.href)),
    caption:shot.querySelector('figcaption').textContent
  })).filter(shot=>shot.src.origin===location.origin);
  if(!shots.length)throw new Error('No screenshots available');
  const chapterNames=new Map([...source.querySelectorAll('.shot-chapters [data-chapter]')].map(button=>[button.dataset.chapter,button.textContent]));
  const controls=element('div','search-controls');
  const label=element('label','','Settings page');label.htmlFor='screenshot-topic';
  const select=element('select');select.id='screenshot-topic';
  const all=element('option','','All screenshots');all.value='all';select.append(all);
  for(const chapter of new Set(shots.map(shot=>shot.chapter))){const option=element('option','',chapterNames.get(chapter)||chapter);option.value=chapter;select.append(option);}
  const selectBox=element('div');selectBox.append(label,select);controls.append(selectBox);
  const figure=element('figure','gallery-image');
  const image=element('img');image.width=1600;image.height=854;image.alt='Back Button Hero app settings';
  const caption=element('figcaption');figure.append(image,caption);
  const navigation=element('div','gallery-navigation');
  const previous=element('button','','Previous'),next=element('button','','Next');previous.type=next.type='button';
  const count=element('span');count.setAttribute('role','status');navigation.append(previous,count,next);
  const full=element('a','text-link','Open full-size image');full.target='_blank';full.rel='noopener';
  let filtered=shots,index=0;
  const display=()=>{const shot=filtered[index];image.src=shot.src.href;full.href=shot.src.href;caption.textContent=shot.caption;count.textContent=`${index+1} / ${filtered.length}`;previous.disabled=index===0;next.disabled=index===filtered.length-1;};
  select.addEventListener('change',()=>{filtered=select.value==='all'?shots:shots.filter(shot=>shot.chapter===select.value);index=0;display();});
  previous.addEventListener('click',()=>{if(index>0){index--;display();}});
  next.addEventListener('click',()=>{if(index<filtered.length-1){index++;display();}});
  display();body.replaceChildren(paragraph('The real app, page by page. Pick a topic or browse the screenshots.'),controls,figure,navigation,full,routeLink('Try these controls in the app simulation','simulation'));
}
function renderAbout(source){
  const fragment=document.createDocumentFragment();
  fragment.append(element('h3','about-heading','Your setup stays on your Thor.'),paragraph('No ads, account or tracking. Your settings, app list, screenshots, macros and recordings stay on your device. GitHub is used for update checks and update downloads.','about-copy'));
  fragment.append(element('h3','about-heading','Independent and open source.'),paragraph('Back Button Hero is an independent project, not affiliated with AYN. Project-owned work is distributed under 0BSD. Existing third-party components keep their own licenses.','about-copy'));
  const github=element('a','text-link','Read the source on GitHub');github.href='https://github.com/denissteinhorst/thor-back-button-hero';fragment.append(github);
  fragment.append(element('h3','about-heading','AI assistance disclosure'));
  for(const p of source.querySelectorAll('#ai p')){const clone=copyContent(p);clone.className='about-copy';fragment.append(clone);}
  body.replaceChildren(fragment);
}
const dialog=document.querySelector('.detail-dialog');
const body=dialog.querySelector('.detail-body');
const title=dialog.querySelector('#detail-title');
const routeTitles={actions:'Find your shortcut.',faq:'Questions, answered.',setup:'Let’s get you set up.',compatibility:'Your frontend. Your setup.',updates:'What’s new.',about:'Your Thor. Your data.',screenshots:'A look inside the app.',film:'Back Button Hero 2.0',simulation:'Try the app.'};
let sequence=0,returnHash='',opener=null,pendingTopic=null;
// Preserve links shared before the homepage moved to dialog routes.
const legacyRoutes={install:'setup',frontends:'compatibility',changelog:'updates',ai:'about','action-catalog':'actions','whats-new':'updates'};
const routeFromHash=()=>legacyRoutes[location.hash.slice(1)]||location.hash.slice(1);
async function renderRoute(route){
  const ownSequence=++sequence;
  if(!routeTitles[route]){if(dialog.open)dismissDialog();return;}
  dialog.dataset.view=route;title.textContent=routeTitles[route];
  for(const link of dialog.querySelectorAll('.detail-nav a')){if(link.dataset.route===route)link.setAttribute('aria-current','page');else link.removeAttribute('aria-current');}
  body.replaceChildren(paragraph('Loading…'));body.scrollTop=0;
  if(!dialog.open){opener=document.activeElement;dialog.showModal();}
  title.focus();
  if(route==='film'||route==='simulation'){
    const frame=element('iframe','media-frame');frame.title=route==='film'?'Back Button Hero 2.0 — film':'Interactive app simulation';
    frame.src=route==='film'?'thor-ad-v2/embed.html':'app-simulation.html?theme=light&v=direct-1';frame.allow='fullscreen';frame.allowFullscreen=true;
    body.replaceChildren(frame);return;
  }
  try{
    const source=await sourceDocument();if(ownSequence!==sequence)return;
    if(route==='actions'){renderActions(source,pendingTopic||actionTopic);pendingTopic=null;}
    else if(route==='faq')renderFAQ(source);
    else if(route==='setup')renderSetup(source);
    else if(route==='about')renderAbout(source);
    else if(route==='screenshots')renderScreenshots(source);
    else if(route==='compatibility'){
      const cards=[...source.querySelectorAll('#frontends .fe-card')].map(copyContent);
      body.replaceChildren(paragraph('Dual-screen frontends behave differently. Here is what has been tested, including known limitations.'),...cards);
    } else if(route==='updates'){
      const releases=[...source.querySelectorAll('#changelog .release')].slice(0,3).map(copyContent);
      const all=element('a','text-link','All releases on GitHub');all.href='https://github.com/denissteinhorst/thor-back-button-hero/releases';body.replaceChildren(...releases,all);
    }
  }catch{
    if(ownSequence!==sequence)return;
    const retry=element('button','button','Try again');retry.type='button';retry.addEventListener('click',()=>renderRoute(route));
    const guide=element('a','text-link','Read the original guide');guide.href='guide.html';body.replaceChildren(paragraph('The details could not load. Your place on this page is saved; try again or open the full guide.'),retry,guide);
  }
}
function dismissDialog(){
  sequence++;
  body.replaceChildren();
  dialog.close();
  if(opener?.isConnected&&!dialog.contains(opener))opener.focus();
}
function closeDetails(){
  if(routeTitles[routeFromHash()])history.replaceState(null,'',location.pathname+location.search+returnHash);
  dismissDialog();
}
if(typeof dialog.showModal==='function'){
  document.addEventListener('click',event=>{
    const anchor=event.target.closest('a[data-route]');if(!anchor||event.metaKey||event.ctrlKey||event.shiftKey||event.altKey)return;
    event.preventDefault();const route=anchor.dataset.route;pendingTopic=route==='actions'?anchor.dataset.topic||'all':null;
    if(anchor.dataset.topic)actionQuery='';
    if(!routeTitles[routeFromHash()])returnHash=location.hash;
    if(routeFromHash()!==route)history.pushState(null,'',`#${route}`);
    renderRoute(route);
  });
  window.addEventListener('hashchange',()=>renderRoute(routeFromHash()));
  dialog.querySelector('.close-detail').addEventListener('click',closeDetails);
  dialog.addEventListener('cancel',event=>{event.preventDefault();closeDetails();});
  dialog.addEventListener('click',event=>{if(event.target===dialog)closeDetails();});
  renderRoute(routeFromHash());
}
