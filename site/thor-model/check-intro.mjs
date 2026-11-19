// SPDX-License-Identifier: 0BSD
import assert from 'node:assert/strict';
import {INTRO_ASPECT,INTRO_POSES,introStoryTiming as introTiming,sampleIntroStory as sampleIntro,createIntroTransport,flickEase} from './intro-timeline.js';
import {sampleIntro as sampleAd,introTiming as adTiming} from './intro-timeline.js';
import {storyToAd,adToStory} from './intro-ad-clock.js';
import {sampleAtmosphere,sampleBirds} from './intro-atmosphere.js';

assert.equal(INTRO_ASPECT,732/442);
assert.equal(flickEase(0),0);assert.equal(flickEase(1),1);
assert.ok(flickEase(.25)>.75,'opening needs a fast initial flick');
assert.ok(flickEase(.5)-flickEase(.25)>flickEase(.75)-flickEase(.5),'hinge must decelerate toward 150 degrees');
assert.deepEqual(sampleBirds(0),[],'birds appear before the logo');
assert.equal(sampleBirds(1).length,5);
assert.ok(sampleBirds(.3).every(b=>Math.hypot(b.x-768,b.y-432)>209),'birds remain trapped behind the logo at the end of the three-second segment');
assert.ok(sampleBirds(1).every(b=>b.y<0),'birds do not leave through the top of the display');
assert.notDeepEqual(sampleAtmosphere(1),sampleAtmosphere(2),'background does not move');
for(let frame=0;frame<3600;frame++){
  const time=frame/60,state=sampleAtmosphere(time);
  const margin=(state.zoom-1)/2;
  assert.ok(Math.abs(state.x)<margin&&Math.abs(state.y)<margin,'background movement exposes a blank border');
  assert.ok(state.lava>=.1&&state.lava<=.34,'lava pulse is outside the restrained brightness range');
}
for(const progress of [0,.1,.5,.9,1]){
  const birds=sampleBirds(progress);sampleBirds(1-progress);
  assert.deepEqual(sampleBirds(progress),birds,'reverse seeking changes the flock');
}
assert.equal(adTiming().duration,30);assert.equal(adTiming().posterStart,26);
assert.equal(createIntroTransport().getState().duration,30);
for(const [story,ad] of [[0,0],[18,11.2],[23,14.2],[31.8,18],[43.8,26],[47,30]]){
  assert.equal(storyToAd(story),ad);assert.equal(adToStory(ad),story);
}
assert.equal(sampleAd(30).hotkeyDemo.final,1);assert.equal(sampleAd(30).viewZoom,.22);
assert.equal(sampleAd(18).hotkeyDemo.highlightTime,0);assert.equal(sampleAd(30).optionScreen,0);
assert.throws(()=>createIntroTransport(31),RangeError);
const defaultTiming=introTiming();
assert.equal(defaultTiming.duration,47);
assert.ok(Math.abs(defaultTiming.introEnd-defaultTiming.openingEnd-.8/6)<1e-12);
assert.ok(defaultTiming.introEnd<defaultTiming.cameraEnd&&defaultTiming.cameraEnd<defaultTiming.wipeEnd&&defaultTiming.wipeEnd<defaultTiming.pressEnd&&defaultTiming.pressEnd<5);
assert.equal(sampleIntro(50/60).brandOpacity,0);assert.ok(sampleIntro(51/60).brandOpacity>0);
assert.equal(sampleIntro(68/60).brandOpacity,1);
assert.equal(defaultTiming.radialStart,5);assert.equal(defaultTiming.radialEnd,7.1);assert.equal(defaultTiming.reachStart,8);
assert.equal(defaultTiming.firstDemoEnd,9);assert.equal(defaultTiming.settingsEnd,10);assert.equal(defaultTiming.toggleEnd,11.2);assert.equal(defaultTiming.mirrorEnd,12.2);
assert.equal(defaultTiming.homeRadialStart,13);assert.equal(defaultTiming.homeRadialEnd,15.1);assert.equal(defaultTiming.homeReachStart,16);assert.equal(defaultTiming.homeDemoEnd,17);
assert.deepEqual(sampleIntro(10).cameraPosition,INTRO_POSES.settings.position);assert.equal(sampleIntro(10).hingeAngle,110);
assert.deepEqual(sampleIntro(12.2).cameraPosition,INTRO_POSES.mirrored.position);assert.equal(sampleIntro(12.2).hingeAngle,150);
assert.equal(sampleIntro(10).optionProgress,0);assert.equal(sampleIntro(11.2).optionProgress,1);
assert.equal(sampleIntro(13).homePressure,1);assert.equal(sampleIntro(13).backPressure,0);
assert.equal(sampleIntro(13).radialOrigin,'home');assert.equal(sampleIntro(16.25).reachFlash,0);
assert.equal(sampleIntro(18).cameraType,'orthographic');assert.equal(sampleIntro(18).projectionMorph,1);
assert.equal(sampleIntro(5).radialProgress,0);assert.equal(sampleIntro(5.3).radialProgress,0);
assert.equal(sampleIntro(7.1).radialProgress,1);
assert.ok(sampleIntro(5.6).radialProgress<.05,'laser must accelerate gently after its start hold');
assert.equal(sampleIntro(7.1).radialColour,1);assert.equal(sampleIntro(8).radialColour,0);
assert.ok(sampleIntro(7.5).radialColour>0&&sampleIntro(7.5).radialColour<1);
assert.equal(sampleIntro(8.25).reachFlash,0);assert.equal(sampleIntro(8.75).reachFlash,0);
for(const [i,keys] of [['L1','R1'],['L2','R2'],['L3','R3'],['start','select'],['home','back']].entries()){
  const pressed=sampleIntro(18.4+i*.8),released=sampleIntro(18.9+i*.8);
  assert.equal(pressed.pauseDemo.rows,i+1);assert.equal(pressed.pauseDemo.pressure,1);
  for(const key of keys)assert.equal(pressed.buttonPressures[key],1);
  assert.equal(released.pauseDemo.pressure,0);
}
assert.equal(sampleIntro(23).pauseDemo.rows,5);assert.equal(sampleIntro(23).pauseDemo.choose,1);
assert.ok(Object.values(sampleIntro(23).buttonPressures).every(v=>v===0));
assert.deepEqual(sampleIntro(defaultTiming.introEnd).cameraPosition,INTRO_POSES.open.position);
assert.ok(Math.abs(sampleIntro(defaultTiming.openingStart+(defaultTiming.openingEnd-defaultTiming.openingStart)*.25).hingeAngle-150*(1-.75**5))<1e-12,'opening loses its flick ease');
for(const duration of [2,3,6,12,60]) {
  const timing=introTiming(duration),start=sampleIntro(0,duration),end=sampleIntro(duration,duration);
  assert.equal(start.hingeAngle,0);assert.equal(start.titleOpacity,1);assert.equal(start.screenOpacity,0);assert.equal(start.brandOpacity,0);
  assert.deepEqual(start.cameraPosition,INTRO_POSES.closed.position);assert.deepEqual(start.cameraTarget,INTRO_POSES.closed.target);
  assert.deepEqual(end.cameraPosition,INTRO_POSES.front.position);assert.deepEqual(end.cameraTarget,INTRO_POSES.front.target);
  assert.deepEqual(end.cameraQuaternion,INTRO_POSES.front.quaternion);
  assert.deepEqual(sampleIntro(timing.introEnd,duration).cameraPosition,INTRO_POSES.open.position);
  assert.equal(end.wireProgress,0);assert.equal(end.backPressure,0);assert.equal(end.homePressure,0);
  assert.equal(end.hingeAngle,180);assert.equal(end.titleOpacity,1);assert.equal(end.screenOpacity,1);assert.equal(end.brandOpacity,1);
  assert.ok(Math.abs(timing.introEnd-timing.openingEnd-(.8/6)*duration/47)<1e-12,'logo stand must remain a sixth of the original duration');
  let previous=0;
  for(let frame=0;frame<=600;frame++) {
    const t=duration*frame/600,state=sampleIntro(t,duration);
    if(t<=timing.firstDemoEnd)assert.ok(state.hingeAngle>=previous,'initial opening must be monotonic');previous=state.hingeAngle;
    if(t<=timing.openingStart)assert.equal(state.hingeAngle,0,'opening starts before the introductory hold ends');
    assert.equal(state.titleOpacity,1,'outer-lid title should disappear through geometric occlusion, not a fade');
    if(state.hingeAngle>=100)assert.equal(state.screenOpacity,1,'background must be fully visible by 100 degrees');
    if(t<=timing.brandStart)assert.equal(state.brandOpacity,0,'logo appears before the hold ends');
    if(t<=timing.cameraEnd)assert.equal(state.wireProgress,0,'wireframe appears before the camera arrives');
    if(t<=timing.wipeEnd)assert.equal(state.backPressure,0,'Back is pressed before the full wireframe');
    if(t>=timing.pressEnd&&t<=timing.firstDemoEnd)assert.equal(state.backPressure,1,'Back is released during its held demonstration');
    if(t>=timing.settingsEnd&&t<timing.frontEnd)assert.equal(state.backPressure,0,'physical Back remains pressed during the Home demonstration');
    if(t>=timing.homePressEnd&&t<=timing.homeDemoEnd)assert.equal(state.homePressure,1,'Home is released during its held demonstration');
    if(t<timing.radialStart)assert.equal(state.radialProgress,0,'radial sweep begins before the original scene ends');
    if(t>=timing.radialEnd&&t<timing.firstDemoEnd||t>=timing.homeRadialEnd&&t<timing.homeDemoEnd)assert.equal(state.radialProgress,1,'radial sweep does not finish before colour fading');
    if(t>timing.introEnd)assert.ok(Math.abs(Math.hypot(...state.cameraQuaternion)-1)<1e-12,'interpolated camera rotation is not normalized');
    if(t>=timing.openingEnd&&t<=timing.firstDemoEnd)assert.equal(state.hingeAngle,150,'hinge moves during the first held demonstration');
    for(const key of ['titleOpacity','screenOpacity','brandOpacity','radialColour'])assert.ok(state[key]>=0&&state[key]<=1);
    sampleIntro(duration-t,duration);
    assert.deepEqual(sampleIntro(t,duration),state,'reverse scrubbing changes a frame');
  }
  assert.deepEqual(sampleIntro(-5,duration),start);assert.deepEqual(sampleIntro(duration+5,duration),end);
}
const transport=createIntroTransport(3);
transport.play(1000);transport.tick(1850);assert.equal(transport.getState().time,.85);
transport.pause();transport.tick(9000);assert.equal(transport.getState().time,.85);
transport.play(10000);transport.tick(10150);assert.equal(transport.getState().time,1);
transport.seek(2);assert.deepEqual(transport.getState(),{time:2,duration:3,playing:false});
transport.play(20000);transport.setDuration(6);assert.equal(transport.getState().time,4);
transport.tick(20100);transport.tick(21100);assert.equal(transport.getState().time,5);
transport.tick(22100);assert.deepEqual(transport.getState(),{time:6,duration:6,playing:false});
transport.play(23000);assert.equal(transport.getState().time,0);
transport.tick(24000);transport.stop();assert.deepEqual(transport.getState(),{time:0,duration:6,playing:false});
transport.seek(100);assert.equal(transport.getState().time,6);transport.seek(-1);assert.equal(transport.getState().time,0);
for(const duration of [0,.5,1,61,NaN,Infinity,'3'])assert.throws(()=>introTiming(duration),RangeError);
assert.throws(()=>sampleIntro(NaN),RangeError);assert.throws(()=>transport.seek(NaN),RangeError);
console.log('Intro checks passed: exact owner poses, flick ease-out, frame-50 logo fade, shorter logo stand, background margins, deterministic flock, variable duration, reverse seeking, pause/resume and stop.');
