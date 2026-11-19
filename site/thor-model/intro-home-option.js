// SPDX-License-Identifier: 0BSD
// The movie demonstrates the switch, rather than reproducing a settings page.
export function drawHomeOption(c,frame) {
  if(!(frame.optionScreen>0))return;
  const p=frame.optionProgress;
  c.save();c.globalAlpha=frame.optionScreen;
  c.fillStyle='#111b22';c.fillRect(0,0,1536,864);
  c.textAlign='center';c.textBaseline='middle';
  c.fillStyle='#a6c6ce';c.font='600 36px Arial, sans-serif';c.fillText('Support for both hands',768,124);
  c.fillStyle='#f1f6f7';c.font='600 104px Arial, sans-serif';
  c.fillText('Use the Home button',768,280,1390);c.fillText('as Back too',768,410);
  const mix=(a,b)=>Math.round(a+(b-a)*p);
  c.fillStyle=`rgb(${mix(56,103)},${mix(75,213)},${mix(87,138)})`;
  c.beginPath();c.roundRect(600,550,336,160,80);c.fill();
  c.fillStyle='#f4f9f8';c.beginPath();c.arc(680+176*p,630,62,0,Math.PI*2);c.fill();
  if(frame.optionTap>0&&frame.optionTap<1){
    c.globalAlpha=frame.optionScreen*(1-frame.optionTap)*.6;c.strokeStyle='#c6fff0';c.lineWidth=6;
    c.beginPath();c.arc(856,630,62+frame.optionTap*90,0,Math.PI*2);c.stroke();
  }
  c.restore();
}
