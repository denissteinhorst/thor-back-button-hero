// SPDX-License-Identifier: 0BSD
import {createStudio} from './studio.js?v=hierarchy-17';
import {draw} from './graphics.js?v=hierarchy-17';
import {sample,CHAPTERS,DURATION} from './timeline.js?v=hierarchy-17';
const $=id=>document.getElementById(id),canvas=$('film'),ctx=canvas.getContext('2d');let studio,logo,time=0,playing=false,anchor=0,origin=0,recording=false,recorder,stream,url,previous=-1;
function display(){draw(ctx,sample(time),studio,logo);$('seek').value=time;$('time').textContent=`${String(Math.floor(time/60)).padStart(2,'0')}:${(time%60).toFixed(2).padStart(5,'0')} / 00:30`;CHAPTERS.forEach(([start],i)=>$('chapters').children[i].setAttribute('aria-current',String(time>=start&&time<(CHAPTERS[i+1]?.[0]??31))));previous=time;}
function pause(){playing=false;$('play').textContent='Abspielen';}function play(){if(time>=30)time=0;origin=time;anchor=performance.now();playing=true;$('play').textContent='Pause';}function seek(t){pause();time=Math.max(0,Math.min(30,t));display();}
CHAPTERS.forEach(([start,name])=>{const b=document.createElement('button');b.textContent=String(start).padStart(Number.isInteger(start)?2:4,'0')+'  '+name;b.disabled=true;b.onclick=()=>seek(start);$('chapters').append(b);});
$('play').onclick=()=>playing?pause():play();$('restart').onclick=()=>seek(0);$('seek').oninput=e=>seek(+e.target.value);$('fullscreen').onclick=()=>document.querySelector('.player').requestFullscreen().catch(()=>{$('status').textContent='Vollbild nicht verfügbar.';});document.addEventListener('keydown',e=>{if(recording||e.target.matches('button,input,a'))return;if(e.code==='Space'){e.preventDefault();playing?pause():play();}if(e.code==='ArrowRight')seek(time+1/60);if(e.code==='ArrowLeft')seek(time-1/60);});
$('still').onclick=()=>{pause();display();$('still-download').href=canvas.toDataURL('image/png');$('still-download').download=`thor-film-${time.toFixed(2)}s.png`;$('still-download').hidden=false;};
function format(){return window.MediaRecorder&&['video/mp4;codecs=avc1.42002a','video/webm;codecs=vp9','video/webm;codecs=vp8'].find(t=>MediaRecorder.isTypeSupported(t));}
const exportProfiles={full:{fps:60,bitrate:14000000,suffix:'',label:'60 fps'},social:{fps:30,bitrate:2000000,suffix:'-social',label:'Social Media · 30 fps'}};
function exportVideo(profile){
 pause();time=0;display();const chunks=[];let aborted=false;
 const setControls=disabled=>document.querySelectorAll('button,input').forEach(b=>b.disabled=disabled);
 const visibility=()=>{if(document.hidden&&recording){aborted=true;pause();if(recorder.state!=='inactive')recorder.stop();$('status').textContent='Aufnahme unterbrochen: Bitte den Tab im Vordergrund lassen.';}};
 try{
  stream=canvas.captureStream(profile.fps);recorder=new MediaRecorder(stream,{mimeType:format(),videoBitsPerSecond:profile.bitrate});recording=true;setControls(true);$('download').hidden=true;
  recorder.ondataavailable=e=>{if(e.data.size)chunks.push(e.data);};
  recorder.onstop=()=>{
   recording=false;document.removeEventListener('visibilitychange',visibility);stream.getTracks().forEach(t=>t.stop());setControls(false);if(aborted)return;
   if(url)URL.revokeObjectURL(url);const blob=new Blob(chunks,{type:recorder.mimeType});url=URL.createObjectURL(blob);const extension=recorder.mimeType.includes('mp4')?'mp4':'webm';
   $('download').href=url;$('download').download=`thor-back-button-hero-2.0-720x1280${profile.suffix}.${extension}`;$('download').textContent=`${profile.suffix?'Social-Media-Video':'Video'} herunterladen · ${(blob.size/1000000).toLocaleString('de-DE',{maximumFractionDigits:1})} MB · ${extension.toUpperCase()}`;$('download').hidden=false;
   $('status').textContent=`Aufnahme bereit · ${profile.label} · 720 × 1280 · ohne Audiospur.`+(profile.suffix&&extension==='webm'?' Dieser Browser bietet WebM an. Für MP4 bitte einen Browser mit MP4-Aufnahme verwenden.':'');
  };
  recorder.onerror=()=>{aborted=true;pause();if(recorder.state!=='inactive')recorder.stop();$('status').textContent='Aufnahme fehlgeschlagen.';};
  document.addEventListener('visibilitychange',visibility);recorder.start();play();$('status').textContent=`Aufnahme läuft · ${profile.label}. Den Tab 30 Sekunden im Vordergrund lassen.`;
 }catch(e){recording=false;document.removeEventListener('visibilitychange',visibility);stream?.getTracks().forEach(t=>t.stop());setControls(false);$('status').textContent=e.message;}
}
$('export').onclick=()=>exportVideo(exportProfiles.full);
$('export-social').onclick=()=>exportVideo(exportProfiles.social);
function tick(now){if(playing){time=Math.min(30,origin+(now-anchor)/1000);if(time>=30)pause();}if(time!==previous)display();if(recording&&time>=30&&recorder.state==='recording')recorder.stop();requestAnimationFrame(tick);}
try{studio=await createStudio();logo=new Image();logo.src='../assets/icon.png';await logo.decode();$('loading').hidden=true;document.querySelectorAll('button,input').forEach(b=>b.disabled=false);$('export').disabled=$('export-social').disabled=!format();display();window.film={seek,play,pause,get time(){return time;},get playing(){return playing;},sample};requestAnimationFrame(tick);if(new URLSearchParams(location.search).has('autoplay'))play();}catch(e){$('loading').textContent='Die 3D-Vorschau konnte nicht starten: '+e.message;console.error(e);}
window.addEventListener('pagehide',()=>{pause();if(recorder?.state==='recording')recorder.stop();stream?.getTracks().forEach(t=>t.stop());if(url)URL.revokeObjectURL(url);});
