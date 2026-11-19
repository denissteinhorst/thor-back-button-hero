// SPDX-License-Identifier: 0BSD
import {registerHooks} from 'node:module';
import assert from 'node:assert/strict';
registerHooks({resolve(s,c,next){return next(s==='three'?new URL('./vendor/three.module.js',import.meta.url).href:s,c);}});
const THREE=await import('three');
const {createThor}=await import('./thor.js');
const {createDesignMode}=await import('./design-mode.js');
const {createIntroWireframe}=await import('./intro-wireframe.js');
const {createBackHoldHint}=await import('./intro-back-hint.js');
const {sampleIntroStory:sampleIntro,introStoryTiming:introTiming}=await import('./intro-timeline.js');
const timing=introTiming();
globalThis.document={createElement(){return {width:0,height:0,getContext:()=>({fillRect(){},strokeRect(){},fillText(){}})};}};
const thor=createThor({markTexture:()=>null}),scene=new THREE.Scene(),camera=new THREE.PerspectiveCamera(33,732/442,1,1500);
scene.add(thor.root);thor.setAngle(150);
const design=createDesignMode(thor.root),floor=new THREE.Mesh(new THREE.PlaneGeometry(1000,1000),new THREE.MeshBasicMaterial());scene.add(floor);
const content=new THREE.Mesh(new THREE.PlaneGeometry(100,60),new THREE.MeshBasicMaterial());content.name='intro-screen-content';thor.hinge.add(content);
const watermark=new THREE.Mesh(new THREE.PlaneGeometry(30,30),new THREE.MeshBasicMaterial());watermark.name='intro-wire-watermark';watermark.visible=false;thor.root.getObjectByName('upper-active-display').add(watermark);
const scissor=new THREE.Vector4(0,0,732,442),draws=[];
let scissorTest=false;
const renderer={autoClear:true,shadowMap:{autoUpdate:true},getScissorTest:()=>scissorTest,getScissor:out=>out.copy(scissor),getSize:out=>out.set(732,442),getPixelRatio:()=>2,
  setScissorTest(value){scissorTest=value;},setScissor(...args){if(args.length===1)scissor.copy(args[0]);else scissor.set(...args);},clear(){},initTexture(){},
  render(){draws.push({design:design.getState(),scissor:scissor.clone(),scissorTest,content:content.visible,watermark:watermark.visible,floor:floor.visible,override:scene.overrideMaterial});}};
const wire=createIntroWireframe(renderer,scene,camera,thor,design,floor);
const hint=createBackHoldHint(thor,scene,renderer);
function pose(time){const frame=sampleIntro(time);camera.position.fromArray(frame.cameraPosition);camera.quaternion.fromArray(frame.cameraQuaternion);camera.updateMatrixWorld();thor.inputs.setButton('back',frame.backPressure);return frame;}
wire.render(pose(3));assert.equal(draws.length,1);assert.equal(draws[0].design.enabled,false);draws.length=0;
wire.render(pose((timing.cameraEnd+timing.wipeEnd)/2));assert.equal(draws.length,3);
assert.equal(draws[0].design.enabled,false);assert.equal(draws[0].content,true);
assert.equal(draws[0].watermark,false);assert.equal(draws[1].watermark,true);assert.equal(draws[2].watermark,false,'glint must not illuminate the entire watermark plane');
assert.equal(draws[1].design.enabled,true);assert.equal(draws[1].design.solidControls,false);assert.equal(draws[1].content,false);assert.equal(draws[1].floor,false);
assert.equal(draws[0].scissor.x,draws[1].scissor.z);assert.equal(draws[0].scissor.z+draws[1].scissor.z,732,'wipe leaves an uncovered column');
assert.ok(draws[2].override);assert.equal(draws[2].scissorTest,false);
assert.equal(renderer.autoClear,true);assert.equal(renderer.shadowMap.autoUpdate,true);assert.equal(scene.overrideMaterial,null);assert.equal(scissorTest,false);
assert.equal(content.visible,true);assert.equal(floor.visible,true);draws.length=0;
const held=pose(timing.firstDemoEnd);wire.render(held);hint.update(held,camera);
assert.equal(draws.length,1);assert.equal(draws[0].design.solidControl,'back');assert.equal(draws[0].content,false);
assert.equal(draws[0].watermark,true,'watermark must remain visible throughout the wireframe hold');
assert.equal(thor.inputs.getState().buttons.back,1);
const caption=scene.getObjectByName('intro-hold-caption'),position=caption.position.clone().project(camera);
assert.ok(position.x<0&&position.y<0,'caption is not below-left of the device');
const depth=-caption.position.clone().applyMatrix4(camera.matrixWorldInverse).z;
const half=depth*Math.tan(THREE.MathUtils.degToRad(33/2));
assert.ok(position.x-caption.scale.x/(2*half*camera.aspect)>-1);assert.ok(position.y-caption.scale.y/(2*half)>-1,'caption is clipped below the frame');
const slot=thor.root.getObjectByName('microsd-cover');if(!slot.geometry.boundingBox)slot.geometry.computeBoundingBox();
const box=slot.geometry.boundingBox,port=new THREE.Vector3(box.min.x,box.min.y,box.max.z).applyMatrix4(slot.matrixWorld).project(camera);
const topRight=new THREE.Vector2(position.x+caption.scale.x/(2*half*camera.aspect),position.y+caption.scale.y/(2*half));
assert.ok(Math.abs(port.x-topRight.x-.006)<1e-10&&Math.abs(port.y-topRight.y-.006)<1e-10,'caption top-right corner is not pinned close to the start of the SD slot');
const ring=thor.root.getObjectByName('intro-back-hint').children[0],pulse=[];
for(const progress of [0,.25,.5,.75,1]){const frame=pose(timing.pressEnd+(timing.radialStart-timing.pressEnd)*progress);hint.update(frame,camera);pulse.push(ring.material.opacity);assert.equal(frame.backPressure,1);}
assert.ok(pulse[0]>.99&&pulse[2]>.99&&pulse[4]>.99&&pulse[1]<.41&&pulse[3]<.41,'held ring does not pulse twice');
hint.hide();wire.reset();assert.equal(caption.visible,false);assert.equal(design.getState().enabled,false);assert.equal(design.getState().solidControl,undefined);
design.dispose();
console.log('Feature checks passed: complementary wipe passes, isolated normal Back button, renderer restoration, outside lower-left caption, two pulses and continuous hold.');
