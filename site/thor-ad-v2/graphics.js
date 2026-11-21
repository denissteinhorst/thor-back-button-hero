// SPDX-License-Identifier: 0BSD
import {ease,clamp} from './timeline.js';
import {smooth} from './motion.js';
let INK='#22272e';
const PINK='#b42a71',GREEN='#66ff00';
const TYPEFACE='-apple-system, BlinkMacSystemFont, "Helvetica Neue", Arial, sans-serif';
export const GROUPS=[['SCREENS & APPS',['Launch apps · Home · Back','Top Recents · Bottom app switcher','Swap screens · Move active app','Last app · Navigation flick','Controller focus · Display power','Minimize · Force-close · Restart','Close all / background apps']],['INPUT & PLAY',['Custom gestures & timing','Buttons · D-pad · Sticks','Back-Button Mode · Dual-Mode','Macro recorder & editor','Gyro & calibration','Virtual mouse · Controller layout','Stick lighting · Vibration']],['CAPTURE & MEDIA',['Screenshots: top / bottom / both','Video: top / bottom / both','Microphone · Push-to-talk','Game-audio ducking','Media controls · Volume & mute','Mute microphone']],['POWER & DISPLAY',['Performance modes · Fan control','Brightness · 60 / 120 Hz','Direct power supply','Status overlay','Force landscape','Power menu · Standby']],['ANDROID CONTROLS',['Quick Settings · Notifications','Wi-Fi · Bluetooth · Location','Night Light · Extra Dim','Display · Sound · Cast','Do Not Disturb · Battery Saver']],['EVERYDAY CONVENIENCE',['Pause / resume · Per-app exceptions','Unassigned input passthrough','Controller-friendly app switcher','Supported frontend cards','Binding overview · Action search','Reset previews · Backup / restore','Service repair · Update checks','Light / dark · English / German · TalkBack']]];
function text(c,s,x,y,size=30,color=INK,weight=500){c.fillStyle=color;c.font=`${weight} ${size}px ${TYPEFACE}`;c.fillText(s,x,y);}
function wrappedText(c,value,x,y,width,size){
 c.font=`500 ${size}px ${TYPEFACE}`;const lines=[''];
 for(const word of value.split(' ')){const i=lines.length-1,next=lines[i]?lines[i]+' '+word:word;if(c.measureText(next).width>width&&lines[i])lines.push(word);else lines[i]=next;}
 lines.forEach((line,i)=>text(c,line,x,y+i*20,size,INK,500));return lines.length*20;
}
function box(c,x,y,w,h,r,color){c.fillStyle=color;c.beginPath();c.roundRect(x,y,w,h,r);c.fill();}
function roundLogo(c,logo,x,y,size){
 c.save();c.beginPath();c.arc(x+size/2,y+size/2,size/2,0,Math.PI*2);c.clip();
 c.drawImage(logo,17,17,478,478,x,y,size,size);c.restore();
}
function end(c){text(c,'Thor Back Button Hero',136,88,33,INK,700);text(c,'2.0',136,126,32,INK,700);text(c,'Your Thor. More possibilities.',58,188,31,INK,500);c.fillStyle='#d9dde1';c.fillRect(58,225,604,1);
 GROUPS.forEach(([title,items],i)=>{const x=44+(i%2)*322,y=266+Math.floor(i/2)*246;text(c,title,x,y,18,'#5b646e',700);let baseline=y+30;items.forEach(item=>{baseline+=wrappedText(c,item,x,baseline,304,17)+3;});});
 c.fillStyle='#d9dde1';c.fillRect(58,1010,604,1);text(c,'2,000+ downloads on GitHub',58,1056,27,INK,700);text(c,'Free · Open source · No tracking',58,1094,22,'#59616b');text(c,'Explore & download',58,1150,24,INK,700);text(c,'denissteinhorst.github.io/',58,1187,22);text(c,'thor-back-button-hero/',58,1216,22);
}
export function draw(c,s,studio,logo){
 const dark=s.wire;const blend=(a,b)=>'#'+a.map((v,i)=>Math.round(v+(b[i]-v)*dark).toString(16).padStart(2,'0')).join('');
 const background=blend([248,249,251],[10,20,28]);INK=dark>.22?'#ebf2f6':'#22272e';
 c.clearRect(0,0,720,1280);const bg=c.createLinearGradient(0,0,720,1280);bg.addColorStop(0,background);bg.addColorStop(1,background);c.fillStyle=bg;c.fillRect(0,0,720,1280); if(s.t>=19){
  const entry=smooth((s.t-19)/.85),exit=smooth((s.t-26)/1.5);
  const card=ease((s.t-27.55)/.45);
  // Move the complete renderer beyond the frame before the reading surface appears.
  if(s.t<27.5){
   const product=studio.render(s);
   c.save();c.beginPath();c.rect(0,300,720,860);c.clip();
   const scale=1+.6*entry,x=-830*entry+1580*exit,y=300+15*entry;
   c.drawImage(product,x,y,720*scale,860*scale);c.restore();
  }
  const lines=[['Swap both screens','Back and forth'],['Switch apps','with one key'],['Screenshots','Top, bottom or both'],['Gameplay recording','Optional push-to-talk'],['Apps on both screens','Full gamepad support'],['Volume & brightness','on keybinds'],['Launch any app','with any key'],['Pause BBH','when you need to'],['Gyro support','Mouse mode']];
  c.save();c.globalAlpha=ease((s.t-19)/.3)*(1-ease((s.t-26)/.35));
  text(c,'Top features.',244,210,45,INK,700);
  text(c,'More ways to play.',244,254,28,'#59616b',500);
  lines.forEach((parts,i)=>{
   const age=s.t-(19.12+i*.63),reveal=smooth(age/.46);if(reveal<=0)return;
   const travel=1-Math.pow(1-clamp(age/.78),4);
   const settling=1-travel,y=334+i*94+settling*32;
   const emphasis=.65*(1-smooth((age-.12)/.72));
   const color=`rgb(${Math.round(34+146*emphasis)},${Math.round(39+3*emphasis)},${Math.round(46+67*emphasis)})`;
   c.save();c.globalAlpha*=reveal;
   c.translate(244+settling*18,y);c.scale(1-settling*.025,1-settling*.025);
   c.filter=age<.5?`blur(${(2.5*(1-smooth(age/.5))).toFixed(2)}px)`:'none';
   parts.forEach((part,n)=>text(c,part,0,n*34,n===0?32:27,n===0?color:'#59616b',n===0?600:400));
   c.restore();
  });c.restore();
  if(card>0){c.save();c.globalAlpha=card;c.fillStyle='#f8f9fb';c.fillRect(0,0,720,1280);end(c);c.restore();}
  roundLogo(c,logo,58,58,54);return;
 }
 if(s.t>=4.4){roundLogo(c,logo,58,58,54);}
 const out=1-ease((s.t-23.82)/.18);c.save();c.globalAlpha=out;
 const product=studio.render(s);
 if(s.t<4.4){
  const retreat=1-smooth((s.t-3.8)/.6);
  c.save();c.globalAlpha=smooth((s.t-1.25)/.25)*retreat;
  c.textAlign='center';c.textBaseline='alphabetic';
  text(c,'Back Button Hero',360,112,42,INK,600);
  c.font='600 250px -apple-system, BlinkMacSystemFont, "Helvetica Neue", Arial, sans-serif';
  c.letterSpacing='-12px';c.fillStyle='#1d1d1f';
  c.fillText('2.0',354,334);c.restore();
  c.save();c.globalAlpha=smooth((s.t-1.95)/.25)*retreat;
  c.textAlign='center';text(c,'Built from your feedback.',360,1122,35,INK,500);c.restore();
 }
 c.save();c.globalAlpha*=s.t<.85?smooth(s.t/.85):1;c.translate(360,1060);c.scale(1,.17);const shadow=c.createRadialGradient(0,0,20,0,0,260);shadow.addColorStop(0,'#26344422');shadow.addColorStop(1,'#26344400');c.fillStyle=shadow;c.fillRect(-280,-280,560,560);c.restore();
 c.drawImage(product,0,300);
 for(const [a,b,reverse]of [[300,390,false],[1080,1160,true]]){if(s.t<4.4&&!reverse)continue;const fade=c.createLinearGradient(0,a,0,b);fade.addColorStop(0,reverse?background+'00':background);fade.addColorStop(1,reverse?background:background+'00');c.fillStyle=fade;c.fillRect(0,a,720,b-a);}
 const boundaries=[0,4.4,5.7,8,9.4,13,14.3,19,24];
 const start=boundaries.filter(t=>t<=s.t).at(-1),next=boundaries.find(t=>t>s.t)??24;
 const titleAlpha=(start===0?1:ease((s.t-start)/.18))*(1-ease((s.t-next+.16)/.16));
 c.save();c.globalAlpha*=s.t<4.4?0:titleAlpha;
 c.translate(0,10*(1-smooth((s.t-start)/.35)));
 const hierarchy=s.t<4.4
  ?['2.0','Built from your feedback.','Thor Back Button Hero']
  :s.t<5.7?['Hold Back.','Unlock your shortcuts.','']
  :s.t<8?['Your controls.','Your shortcuts.','Fully customizable.']
  :s.t<9.4?['Back-Button Mode.','Home works as Back, too.','One shared shortcut layer.']
  :s.t<13?['Same shortcuts.','Either side.','Fully customizable.']
  :s.t<14.3?['Dual-Mode.','Two buttons. Two layers.','Enable in settings.']
  :['Two independent','shortcut layers.','Hold Back or Home to choose.'];
 const [headline,subline,detail]=hierarchy;
 text(c,headline,58,209,48,INK,700);
 text(c,subline,58,267,subline.length>23?34:44,INK,600);
 text(c,detail,58,320,30,dark>.22?'#bbc9d3':'#69717b',500);
 c.restore();
 if(s.setting){
  const home=s.setting==='home',local=s.t-(home?8:13),on=ease((local-.45)/.3);
  const accent=home?'#e781b5':GREEN;
  c.save();c.globalAlpha*=ease(local/.16)*(1-ease((local-(home?1.2:1.1))/.2));
  const x=58,y=1074,w=604,h=148;
  c.shadowColor='#00000066';c.shadowBlur=24;c.shadowOffsetY=8;box(c,x,y,w,h,15,'#172934');
  c.shadowColor='transparent';c.shadowBlur=0;c.shadowOffsetY=0;
  c.strokeStyle='#66818e';c.lineWidth=1.5;c.beginPath();c.roundRect(x,y,w,h,15);c.stroke();
  text(c,home?'HOME BUTTON':'HOME SHORTCUTS',x+25,y+34,16,'#a7bbc5',700);
  text(c,home?'Back-Button Mode':'Dual-Mode',x+25,y+80,home?24:29,'#f3f8fa',600);
  const sx=x+w-118,sy=y+49;box(c,sx,sy,78,42,21,on>.5?accent:'#415963');
  c.strokeStyle=on>.5?accent:'#91a6af';c.lineWidth=1;c.beginPath();c.roundRect(sx,sy,78,42,21);c.stroke();
  box(c,sx+4+on*34,sy+4,34,34,17,'#f7fbfc');
  c.textAlign='center';text(c,on>.5?'ON':'OFF',sx+39,y+121,16,on>.5?accent:'#bdcbd1',700);c.restore();
 }
 for(const key of ['back','home']){
  const alpha=key==='back'?ease((s.t-4.6)/.18):s.home;
  if(alpha<=0)continue;
  const p=studio.point(key),color=key==='home'&&s.greenHome>0?GREEN:PINK,side=key==='back'?1:-1;
  const width=176,height=44,x=key==='back'?438:106,y=1018;
  c.save();c.globalAlpha*=alpha*(1-ease((s.t-19)/.45));
  c.strokeStyle=color;c.lineWidth=2.5;c.beginPath();
  c.moveTo(p.x+side*10,p.y+4);
  c.lineTo(p.x+side*50,p.y+38);
  c.lineTo(x+width/2,y-18);c.lineTo(x+width/2,y);c.stroke();
  box(c,x,y,width,height,9,color);
  c.textAlign='center';text(c,key==='back'?'HOLD: Back':'HOLD: Home',x+width/2,y+29,24,'#0a141c',700);c.restore();
 }
 if(s.t>=14.3&&s.t<19){c.save();c.globalAlpha*=ease((s.t-14.3)/.3);
  for(const [i,label,color]of [[0,'01   BACK','#f084be'],[1,'02   HOME',GREEN]]){
   c.save();
   const x=58+i*156;text(c,label,x,356,21,color,700);
   box(c,x,367,132,3,1.5,color);c.restore();
  }c.restore();}
 c.restore();
}
