// SPDX-License-Identifier: 0BSD
import { createIntroTransport, sampleIntro } from './intro-timeline.js?v=ad-1';

export function mountIntroPlayer({onEnter,onLeave,onFrame}) {
  const transport=createIntroTransport();
  const play=document.getElementById('intro-play'),stop=document.getElementById('intro-stop');
  const seek=document.getElementById('intro-seek'),duration=document.getElementById('intro-duration');
  const time=document.getElementById('intro-time'),phase=document.getElementById('intro-phase');
  const intro=document.getElementById('intro-mode'),model=document.getElementById('model-mode');
  let enabled=false;
  let previousTime=null,previousDuration=null;
  function refresh() {
    const state=transport.getState();
    play.textContent=state.playing?'Pause':'Play';play.setAttribute('aria-pressed',String(state.playing));
    seek.max=String(state.duration);seek.value=String(state.time);
    const format=value=>value.toLocaleString('de-DE',{minimumFractionDigits:3,maximumFractionDigits:3});
    time.value=`${format(state.time)} / ${format(state.duration)} s · Frame ${Math.round(state.time*60)} / ${Math.round(state.duration*60)}`;
    seek.setAttribute('aria-valuetext',format(state.time)+' Sekunden');
    const frame=sampleIntro(state.time,state.duration);phase.value=frame.phase;
    if(enabled&&(state.time!==previousTime||state.duration!==previousDuration)){
      onFrame(frame);previousTime=state.time;previousDuration=state.duration;
    }
  }
  function setEnabled(value) {
    if(value===enabled)return;
    transport.pause();enabled=value;
    previousTime=previousDuration=null;
    intro.setAttribute('aria-pressed',String(value));model.setAttribute('aria-pressed',String(!value));
    document.getElementById('intro-transport').hidden=!value;
    if(value)onEnter();else onLeave();
    refresh();
  }
  play.addEventListener('click',()=>{
    transport.tick(performance.now());
    if(transport.getState().playing)transport.pause();else transport.play(performance.now());
    refresh();
  });
  stop.addEventListener('click',()=>{transport.stop();refresh();});
  seek.addEventListener('input',()=>{transport.seek(Number(seek.value));refresh();});
  function updateDuration() {
    if(!duration.checkValidity())return;
    const next=Number(duration.value);
    if(next===transport.getState().duration)return;
    transport.setDuration(next);refresh();
  }
  duration.addEventListener('input',updateDuration);
  duration.addEventListener('change',()=>{
    if(!duration.checkValidity()){duration.value=String(transport.getState().duration);return;}
    updateDuration();
  });
  intro.addEventListener('click',()=>setEnabled(true));model.addEventListener('click',()=>setEnabled(false));
  document.addEventListener('visibilitychange',()=>{if(document.hidden){transport.tick(performance.now());transport.pause();refresh();}});
  intro.disabled=model.disabled=play.disabled=stop.disabled=seek.disabled=duration.disabled=false;
  document.getElementById('intro-load-status').textContent='';
  setEnabled(true);
  return {isEnabled:()=>enabled,tick(now){if(enabled){transport.tick(now);refresh();}}};
}
