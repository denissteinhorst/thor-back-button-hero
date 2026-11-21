// SPDX-License-Identifier: 0BSD
export const DURATION=30;
export const CHAPTERS=[[0,'Version 2.0'],[4.4,'Hold Back'],[8,'Back-Button Mode'],[13,'Dual-Mode'],[19,'Top features'],[26,'Transition'],[28,'Endcard']];
export const CODE=['↑','↑','↓','↓','←','→','←','→','B','A'];
export const clamp=x=>Math.max(0,Math.min(1,x));
export const ease=x=>{x=clamp(x);return x*x*(3-2*x);};
export function sample(t){
 t=Math.max(0,Math.min(30,t));const buttons={};
 const hold=(a,b)=>ease((t-a)/.18)*(1-ease((t-b)/.2));
 buttons.back=hold(4.6,7.2);
 buttons.home=hold(14.5,18.7);
 const pinkFirst=ease((t-5.7)/.75)*(1-ease((t-7.45)/.4));
 const pinkReturn=ease((t-9.8)/.7);
 const green=ease((t-15.05)/1.15);
 const sharedHome=ease((t-8.55)/.3)*(1-ease((t-12.5)/.35));
 const greenHome=ease((t-14.5)/.18);
 const panes=[Math.max(pinkFirst,pinkReturn),green].map(value=>value*(1-ease((t-19)/.6)));
 const paneGlow=[Math.max(ease((t-6.1)/.25)*(1-ease((t-7.45)/.4)),ease((t-10.15)/.18))*(1-ease((t-15.05)/.2)),ease((t-15.525)/.18)];

 const captures=[10.84,12.09,16.49];const capture=captures.reduce((v,a)=>Math.max(v,t>=a?1-ease((t-a)/.65):0),0);
 const title=t<4.4?['2.0','Built from your feedback.']:t<4.7?['Hold Back.','Unlock your shortcuts.']:t<8?['Swap screens.','']:t<9.4?['Back-Button Mode.','']:t<13?['Same shortcuts.','Either side.']:t<14.3?['Dual-Mode.','']:t<16?['One button.','Two assignments.']:t<17.3?['BACK + R1','Screenshot']:t<19?['HOME + R1','Video recording']:t<20?['Swap screens.','Switch apps.']:t<21?['Capture both screens.','Add your voice.']:t<22?['Your apps.','At your fingertips.']:t<23?['Brightness. Volume.','Any app.']:['Gyro. Mouse.','Pause.'];
 return {t,buttons,panes,paneGlow,sharedHome,greenHome,capture,title,swapped:ease((t-4.97)/.5),wire:ease((t-5.1)/.95)*(1-ease((t-18.95)/.5)),rings:ease((t-3.45)/.8)*(1-ease((t-22.75)/.5)),dual:ease((t-14.35)/1.1),home:Math.max(sharedHome,greenHome),record:t>=17.93&&t<19,setting:t>=8&&t<9.4?'home':t>=13&&t<14.3?'dual':null};
}
