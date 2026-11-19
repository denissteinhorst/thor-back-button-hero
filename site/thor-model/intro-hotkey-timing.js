// SPDX-License-Identifier: 0BSD
const smooth=v=>{const x=Math.max(0,Math.min(1,v));return x*x*(3-2*x);};
const step=.24,gap=.18;
export const HOTKEY_CATEGORIES=[
  {label:'System Buttons',keys:['back','home','start','select']},
  {label:'D-Pad',keys:['dpadUp','dpadRight','dpadDown','dpadLeft']},
  {label:'Analogstick (each)',keys:['up','right','down','left','press','double','tapHold']},
  {label:'Shoulder Buttons',keys:['L1','R1','L2','R2']},
  {label:'Face Buttons',keys:['A','B','X','Y']}
];
export function sampleHotkeyInputs(elapsed){
  let start=.15;
  const cues=[],buttons={},sticks={left:{x:0,y:0},right:{x:0,y:0}};
  const rows=HOTKEY_CATEGORIES.map((category,index)=>{
    const local=elapsed-start,slot=Math.floor(local/step),within=local-slot*step;
    const active=slot>=0&&slot<category.keys.length;
    let pressure=active?smooth(within/.055)*(1-smooth((within-.15)/.075)):0;
    const key=category.keys[slot];
    if(index===2&&active){
      if(key==='double')pressure=Math.sin(Math.PI*2*Math.min(1,within/.225))**2;
      if(key==='tapHold')pressure=within<.07?Math.sin(Math.PI*within/.07)**2:smooth((within-.08)/.03)*(1-smooth((within-.19)/.04));
      for(const side of ['left','right']){
        const axis=key==='up'?[0,1]:key==='right'?[1,0]:key==='down'?[0,-1]:key==='left'?[-1,0]:[0,0];
        sticks[side]={x:axis[0]*pressure,y:axis[1]*pressure};
        cues.push({key:side+'Stick',strength:pressure});
        if(slot>=4)buttons[side==='left'?'L3':'R3']=pressure;
      }
    }else if(active){buttons[key]=pressure;cues.push({key:key.startsWith('dpad')?'dpad':key,strength:pressure});}
    const row={start,reveal:smooth(local/.28),count:Math.max(0,Math.min(category.keys.length,slot+1)),countReveal:active?smooth(within/.12):1};
    start+=category.keys.length*step+gap;return row;
  });
  return {rows,cues,buttons,sticks};
}
