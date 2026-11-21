// SPDX-License-Identifier: 0BSD
import {ease} from './timeline.js';
const mix=(a,b,t)=>a+(b-a)*t;
export const smooth=x=>{x=Math.max(0,Math.min(1,x));return x*x*x*(x*(x*6-15)+10);};
export function cameraPose(t){
 const front=smooth((t-.5)/1.7);
 const dual=smooth((t-13.45)/1.65)*(1-smooth((t-18.85)/1.1));
 const returning=smooth((t-26)/1.85);
 const side=1-front+returning;
 const close=smooth((t-3.8)/.6)*(1-smooth((t-4.92)/.78));
 const target=[46.3*close,mix(23,8.1,close),mix(-22,35.6,close)];
 return {position:[mix(70*side+58*dual,91,close),mix(330-155*dual,125,close),mix(245+70*side+100*dual,185,close)],target,half:mix(mix(125,115,front)+returning*9+dual*7,22,close)};
}
export function ringPose(s,{name,index,layer}){
 const birth=layer?14.5:4.05;
 const rise=ease((s.t-birth-index*.004)/(layer?.95:.24));
 const macroVisible=s.t<19||name.startsWith('dpad')||['A','B','X','Y'].includes(name);
 const amount=rise*(name==='home'?1-s.home:1)*(1-ease((s.t-19.6)/.35))*Number(macroVisible);
 const active=s.t>=17.3&&s.t<19?1:0;
 const emphasis=s.t>=16&&s.t<19?(layer===active?1:.18):s.t>=6.9&&s.t<9.4?.5:1;
 return {rise,altitude:(layer?47:23)*amount,opacity:amount*emphasis};
}
