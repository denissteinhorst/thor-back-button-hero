// SPDX-License-Identifier: 0BSD
import { storyToAd, adToStory } from './intro-ad-clock.js?v=ad-1';
import { sampleHotkeyInputs } from './intro-hotkey-timing.js?v=ad-1';
import { samplePauseDemo } from './intro-pause-timing.js?v=ad-1';

export const INTRO_ASPECT=732/442;
export const INTRO_POSES={
  closed:{position:[.42945431227117836,267.71561478929806,243.15020197700014],target:[0,1.1812663555145262,-.129999999999999],quaternion:[-.4036375578530012,.0008075367518009751,.00035626358693411145,.9149185443803816]},
  open:{position:[.42945431227117203,286.89373672917117,208.01285709356125],target:[0,20.35938829538788,-35.267344883439435],quaternion:[-.4036375578530005,.0008075367518009616,.00035626358693410483,.914918544380382]},
  feature:{position:[196.8416016599117,190.89285889993965,214.52800987624698],target:[0,20.35938829538788,-35.267344883439435],quaternion:[-.23018389823190502,.3176668932752042,.07979497471369879,.9163710383475049]},
  settings:{position:[2.238306341578727,234.5618862746226,289.93366703521696],target:[0,39.422903783914556,-13.614957565870512],quaternion:[-.2817919545051007,.0035374153342844113,.0010389243998225489,.9594684474775809]},
  mirrored:{position:[-144.259839559663,222.10906763035723,226.86270981929204],target:[0,20.35938829538788,-35.267344883439435],quaternion:[-.2830998288615799,-.2380358518284894,-.07275512950535512,.926228973458231]},
  front:{position:[4.6936095121252686e-284,361.18126635551454,-39.869999237224256],target:[0,1.1812663555145262,-39.96999923706055],quaternion:[-.707008565094297,1.6596720218313238e-283,1.6592110658565693e-283,.7072049836386217]}
};
export const INTRO_FRUSTUM={left:-158.98642533936652,right:158.98642533936652,top:96,bottom:-96};
const clamp=(v,min,max)=>Math.max(min,Math.min(max,v));
const smooth=v=>{const x=clamp(v,0,1);return clamp(x*x*x*(x*(x*6-15)+10),0,1);};
// A fast opening impulse with a long, quiet settle, without overshooting 150°.
export const flickEase=value=>{const x=clamp(value,0,1);return 1-(1-x)**5;};
// Symmetric acceleration keeps the first targets legible instead of jumping
// straight to maximum velocity. Its inverse also timestamps laser trails.
export const sweepEase=value=>{const x=clamp(value,0,1);return x<.5?4*x*x*x:1-4*(1-x)**3;};
export const sweepInverse=value=>{const x=clamp(value,0,1);return x<.5?Math.cbrt(x/4):1-Math.cbrt((1-x)/4);};
function validateStoryDuration(duration) {
  if(!Number.isFinite(duration)||duration<2||duration>60)throw new RangeError('Intro duration must be between 2 and 60 seconds');
  return duration;
}
export function introStoryTiming(duration=47) {
  validateStoryDuration(duration);
  // The former 0.8-second logo stand is reduced to a sixth. Distribute the
  // remaining five seconds over the other beats without changing their order.
  const scale=duration/47,motion=(5-.8/6)/5.2*scale;
  const openingStart=.7*motion,openingEnd=2.2*motion;
  const introEnd=openingEnd+(.8/6)*scale,cameraEnd=introEnd+motion;
  const wipeEnd=cameraEnd+.8*motion,pressEnd=wipeEnd+.4*motion;
  return {openingStart,openingEnd,brandStart:(50/60)*scale,brandFade:.3*motion,
    introEnd,cameraEnd,wipeEnd,pressEnd,radialStart:5*scale,radialEnd:7.1*scale,reachStart:8*scale,
    firstDemoEnd:9*scale,settingsEnd:10*scale,toggleEnd:11.2*scale,mirrorEnd:12.2*scale,
    homePressEnd:12.5*scale,homeRadialStart:13*scale,homeRadialEnd:15.1*scale,
    homeReachStart:16*scale,homeDemoEnd:17*scale,frontEnd:18*scale,duration};
}
function slerp(a,b,t) {
  if(t===0)return [...a];if(t===1)return [...b];
  let dot=a.reduce((sum,v,i)=>sum+v*b[i],0),end=b;
  if(a.length===4&&dot<0){end=b.map(v=>-v);dot=-dot;}
  const angle=Math.acos(clamp(dot,-1,1)),sine=Math.sin(angle);
  const result=a.map((v,i)=>sine>.00001?(v*Math.sin((1-t)*angle)+end[i]*Math.sin(t*angle))/sine:v+(end[i]-v)*t);
  const length=Math.hypot(...result);return result.map(v=>v/length);
}
function orbitPose(a,b,progress) {
  if(progress===0)return {position:[...a.position],target:[...a.target],quaternion:[...a.quaternion]};
  if(progress===1)return {position:[...b.position],target:[...b.target],quaternion:[...b.quaternion]};
  const target=a.target.map((v,i)=>v+(b.target[i]-v)*progress);
  const offsetA=a.position.map((v,i)=>v-a.target[i]),offsetB=b.position.map((v,i)=>v-b.target[i]);
  const radiusA=Math.hypot(...offsetA),radiusB=Math.hypot(...offsetB);
  const direction=slerp(offsetA.map(v=>v/radiusA),offsetB.map(v=>v/radiusB),progress);
  return {target,position:direction.map((v,i)=>target[i]+v*(radiusA+(radiusB-radiusA)*progress)),quaternion:slerp(a.quaternion,b.quaternion,progress)};
}
export function sampleIntroStory(time,duration=47) {
  if(!Number.isFinite(time))throw new RangeError('Intro time must be finite');
  const timing=introStoryTiming(duration),t=clamp(time,0,duration);
  const opening=flickEase((t-timing.openingStart)/(timing.openingEnd-timing.openingStart));
  const lerp=(a,b)=>a.map((v,i)=>v+(b[i]-v)*opening);
  let hingeAngle=150*opening;
  const orbit=smooth((t-timing.introEnd)/(timing.cameraEnd-timing.introEnd));
  let cameraPosition=lerp(INTRO_POSES.closed.position,INTRO_POSES.open.position);
  let cameraTarget=lerp(INTRO_POSES.closed.target,INTRO_POSES.open.target);
  let cameraQuaternion=lerp(INTRO_POSES.closed.quaternion,INTRO_POSES.open.quaternion);
  if(t>timing.introEnd){
    const pose=orbitPose(INTRO_POSES.open,INTRO_POSES.feature,orbit);
    cameraPosition=pose.position;cameraTarget=pose.target;cameraQuaternion=pose.quaternion;
  }
  let wireProgress=smooth((t-timing.cameraEnd)/(timing.wipeEnd-timing.cameraEnd));
  let backReveal=smooth((t-timing.wipeEnd)/(timing.pressEnd-timing.wipeEnd)),homeReveal=0;
  let holdProgress=clamp((t-timing.pressEnd)/(timing.radialStart-timing.pressEnd),0,1);
  let holdElapsed=Math.max(0,Math.min(t,timing.radialStart)-timing.pressEnd);
  let phase=t<timing.openingStart?'Titel':t<timing.openingEnd?'Aufklappen':t<timing.introEnd?'Logo und Feature preview':t<timing.cameraEnd?'Kamerafahrt':t<timing.wipeEnd?'Wireframe-Wischlicht':t<timing.pressEnd?'Back hervorheben':t<timing.radialStart?'Hold Back':t<timing.radialEnd?'Radialer Button-Sweep':t<timing.reachStart?'Farben ausblenden':'Erreichbarkeit';
  let projectionMorph=0,optionScreen=0,retainButtons=false;
  if(t>=timing.firstDemoEnd){
    const travel=smooth((t-timing.firstDemoEnd)/(timing.settingsEnd-timing.firstDemoEnd));
    const mirror=smooth((t-timing.toggleEnd)/(timing.mirrorEnd-timing.toggleEnd));
    projectionMorph=smooth((t-timing.homeDemoEnd)/(timing.frontEnd-timing.homeDemoEnd));
    let pose;
    if(t<timing.settingsEnd){pose=orbitPose(INTRO_POSES.feature,INTRO_POSES.settings,travel);hingeAngle=150-40*travel;wireProgress=1-travel;backReveal=1-travel;retainButtons=true;phase='Zur Home-Option';}
    else if(t<timing.toggleEnd){pose=orbitPose(INTRO_POSES.settings,INTRO_POSES.settings,0);hingeAngle=110;wireProgress=0;backReveal=0;phase='Home als Back einschalten';}
    else if(t<timing.mirrorEnd){pose=orbitPose(INTRO_POSES.settings,INTRO_POSES.mirrored,mirror);hingeAngle=110+40*mirror;wireProgress=mirror;backReveal=0;phase='Gegenüberliegende Ansicht';}
    else {pose=orbitPose(INTRO_POSES.mirrored,INTRO_POSES.front,projectionMorph);hingeAngle=150+30*projectionMorph;wireProgress=1-projectionMorph;backReveal=0;retainButtons=t>=timing.homeDemoEnd;phase=t<timing.homeRadialStart?'HOLD Home':t<timing.homeRadialEnd?'Home: radialer Sweep':t<timing.homeReachStart?'Home: Farben ausblenden':t<timing.homeDemoEnd?'Home: Erreichbarkeit':'Frontalansicht';}
    cameraPosition=pose.position;cameraTarget=pose.target;cameraQuaternion=pose.quaternion;
    optionScreen=travel*(1-smooth((t-timing.toggleEnd)/(timing.mirrorEnd-timing.toggleEnd)));
    homeReveal=smooth((t-timing.mirrorEnd)/(timing.homePressEnd-timing.mirrorEnd))*(1-projectionMorph);
    holdProgress=clamp((t-timing.homePressEnd)/(timing.homeRadialStart-timing.homePressEnd),0,1);
    holdElapsed=Math.max(0,Math.min(t,timing.homeRadialStart)-timing.homePressEnd);
  }
  const homeDemo=t>=timing.mirrorEnd;
  const radialStart=homeDemo?timing.homeRadialStart:timing.radialStart;
  const radialEnd=homeDemo?timing.homeRadialEnd:timing.radialEnd;
  const reachStart=homeDemo?timing.homeReachStart:timing.reachStart;
  const demoEnd=homeDemo?timing.homeDemoEnd:timing.firstDemoEnd;
  const radialStarted=t>=radialStart&&t<demoEnd;
  const charge=.3*duration/47;
  const sweepProgress=clamp((t-radialStart-charge)/(radialEnd-radialStart-charge),0,1);
  const scale=duration/47;
  const pauseDemo=samplePauseDemo(t,scale);
  const hotkeyElapsed=t/scale-23;
  const hotkeyDemo={elapsed:hotkeyElapsed,opacity:smooth(hotkeyElapsed/.2),
    logoMove:smooth((hotkeyElapsed-7)/.8),labelsElapsed:hotkeyElapsed-8.8,
    inputs:sampleHotkeyInputs(hotkeyElapsed),final:smooth((hotkeyElapsed-20.8)/.7)};
  const viewZoom=(1-.45*smooth((hotkeyElapsed-7.8)/.8))*(1-hotkeyDemo.final)+.22*hotkeyDemo.final;
  // Lift the small product above a full-width final catalogue grid. Frustum
  // translation keeps the supplied camera pose and model transform intact.
  const finalOffset=-.79*INTRO_FRUSTUM.top/viewZoom*hotkeyDemo.final;
  const orthoFrustum={...INTRO_FRUSTUM,top:INTRO_FRUSTUM.top+finalOffset,bottom:INTRO_FRUSTUM.bottom+finalOffset};
  const buttonPressures=Object.fromEntries(['L1','R1','L2','R2','L3','R3','start','select','home','back','A','B','X','Y','dpadUp','dpadRight','dpadDown','dpadLeft'].map(key=>[key,0]));
  buttonPressures.back=backReveal;buttonPressures.home=homeReveal;
  if(t>=timing.frontEnd){
    for(const key of pauseDemo.keys)buttonPressures[key]=pauseDemo.pressure;
    phase=t/scale<23?(pauseDemo.index>=0?'Pause: '+pauseDemo.keys.join(' + '):'Pause: Choose yours'):
      hotkeyElapsed<7?'Possible Hotkeys':hotkeyElapsed<8.8?'Available Functions':hotkeyElapsed<20.8?'Funktionsgruppen':'Alle 33 Funktionen';
  }
  const controlCues=pauseDemo.keys.map(key=>({key:key==='L3'?'leftStick':key==='R3'?'rightStick':key,strength:pauseDemo.pressure}));
  if(hotkeyElapsed>=0){Object.assign(buttonPressures,hotkeyDemo.inputs.buttons);controlCues.push(...hotkeyDemo.inputs.cues);}
  return {
    controlCues,stickPoses:hotkeyDemo.inputs.sticks,
    pauseDemo,hotkeyDemo,viewZoom,buttonPressures,
    time:t,hingeAngle,
    cameraPosition,cameraTarget,cameraQuaternion,
    // Visibility is geometric: the black outer-lid title rotates out of sight.
    titleOpacity:1,
    screenOpacity:smooth(hingeAngle/100),
    brandOpacity:smooth((t-timing.brandStart)/timing.brandFade),
    brandProgress:clamp((t-timing.brandStart)/timing.brandFade,0,1),
    brandElapsed:Math.max(0,t-timing.brandStart),
    wireProgress,backReveal,homeReveal,backPressure:buttonPressures.back,homePressure:buttonPressures.home,
    holdKey:homeDemo?'home':'back',holdReveal:homeDemo?homeReveal:backReveal,holdProgress,holdElapsed,
    projectionMorph,cameraType:projectionMorph===1?'orthographic':'perspective',orthoFrustum,
    optionScreen,optionProgress:smooth((t-10.25*scale)/(.65*scale)),optionTap:clamp((t-10.25*scale)/(.4*scale),0,1),
    retainButtons,radialOrigin:homeDemo?'home':'back',radialStarted,radialProgress:sweepEase(sweepProgress),
    radialElapsed:Math.max(0,t-radialStart-charge),radialDuration:radialEnd-radialStart-charge,
    radialMoving:t>=radialStart+charge,
    radialFade:!radialStarted?0:1-smooth((t-radialEnd)/(.12*scale)),
    radialColour:1-smooth((t-radialEnd)/(reachStart-radialEnd)),
    reachFlash:0,
    phase
  };
}

// The public clock is the finished ad; rig timings above remain authored cues.
export function validateDuration(duration){
  if(!Number.isFinite(duration)||duration<2||duration>30)throw new RangeError('Ad duration must be between 2 and 30 seconds');
  return duration;
}
export function introTiming(duration=30){
  validateDuration(duration);const authored=introStoryTiming(),scale=duration/30;
  const result={};
  for(const [key,value] of Object.entries(authored)){
    result[key]=key==='duration'?duration:key==='brandFade'?
      (storyToAd(authored.brandStart+value)-storyToAd(authored.brandStart))*scale:storyToAd(value)*scale;
  }
  return {...result,highlightsStart:18*scale,posterStart:26*scale};
}
export function sampleIntro(time,duration=30){
  validateDuration(duration);
  if(!Number.isFinite(time))throw new RangeError('Intro time must be finite');
  const actual=Math.max(0,Math.min(duration,time)),adTime=actual/duration*30;
  const frame=sampleIntroStory(adToStory(adTime));
  frame.time=actual;frame.hotkeyDemo.highlightTime=adTime-18;
  frame.hotkeyDemo.final=smooth((adTime-26)/.5);
  if(adTime>=18){
    frame.viewZoom=.55*(1-frame.hotkeyDemo.final)+.22*frame.hotkeyDemo.final;
    const offset=-.79*INTRO_FRUSTUM.top/frame.viewZoom*frame.hotkeyDemo.final;
    frame.orthoFrustum={...INTRO_FRUSTUM,top:INTRO_FRUSTUM.top+offset,bottom:INTRO_FRUSTUM.bottom+offset};
  }
  if(adTime>=18)frame.phase=adTime<26?'Feature-Highlights':'33 Funktionen – Gesamtübersicht';
  return frame;
}

// Playback and scrubbing sample the same absolute timeline, never accumulated
// hinge deltas. Seeking backward therefore reconstructs the very same frame.
export function createIntroTransport(duration=30) {
  validateDuration(duration);
  let time=0,playing=false,anchor=null,origin=0;
  return {
    getState:()=>({time,duration,playing}),
    play(now){if(playing)return;if(time>=duration)time=0;origin=time;anchor=now;playing=true;},
    pause(){playing=false;anchor=null;},
    stop(){time=0;playing=false;anchor=null;},
    seek(value){if(!Number.isFinite(value))throw new RangeError('Seek time must be finite');time=clamp(value,0,duration);playing=false;anchor=null;},
    setDuration(value){validateDuration(value);time=time/duration*value;duration=value;origin=time;anchor=null;},
    tick(now){
      if(!playing)return;
      if(anchor===null){anchor=now;origin=time;}
      time=clamp(origin+(now-anchor)/1000,0,duration);
      if(time===duration){playing=false;anchor=null;}
    }
  };
}
