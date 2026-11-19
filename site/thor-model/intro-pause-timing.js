// SPDX-License-Identifier: 0BSD
export const PAUSE_COMBOS=[
  {label:'L1 + R1',keys:['L1','R1']},
  {label:'L2 + R2',keys:['L2','R2']},
  {label:'L3 + R3',keys:['L3','R3']},
  {label:'START + SELECT',keys:['start','select']},
  {label:'HOME + Back',keys:['home','back']}
];
const clamp=v=>Math.max(0,Math.min(1,v));
const smooth=v=>{const x=clamp(v);return x*x*(3-2*x);};

// All cues are sampled from absolute time, including release gaps, so scrubbing
// does not leave a pair pressed or a monochrome logo behind.
export function samplePauseDemo(time,scale) {
  const elapsed=time/scale-18,first=.2,step=.8;
  const index=Math.floor((elapsed-first)/step),local=elapsed-first-index*step;
  const active=index>=0&&index<PAUSE_COMBOS.length;
  const pressure=active?smooth(local/.09)*(1-smooth((local-.43)/.12)):0;
  return {opacity:smooth(elapsed/.18),elapsed,index:active?index:-1,
    rows:Math.max(0,Math.min(5,index+1)),pressure,
    keys:active?PAUSE_COMBOS[index].keys:[],
    choose:smooth((elapsed-4.2)/.25)};
}
