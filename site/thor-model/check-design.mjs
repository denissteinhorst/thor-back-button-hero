// SPDX-License-Identifier: 0BSD
import {registerHooks} from 'node:module';
import assert from 'node:assert/strict';
registerHooks({resolve(s,c,next){return next(s==='three'?new URL('./vendor/three.module.js',import.meta.url).href:s,c);}});
const THREE=await import('three');
const {createThor}=await import('./thor.js');
const {createDesignMode,designGeometry}=await import('./design-mode.js');
const {root,inputs,setAngle}=createThor({markTexture:()=>null});
inputs.setButton('L2',.7).setDpad(1,1).setStick('left',{x:.6,y:.5,press:1});setAngle(120);
const parts=[];let originalDisposals=0;
root.traverse(o=>{
  if(!o.isMesh)return;
  let control=false;for(let p=o;p;p=p.parent)if(p.userData.inputControl)control=true;
  parts.push({o,geometry:o.geometry,material:o.material,visible:o.visible,cast:o.castShadow,receive:o.receiveShadow,matrix:o.matrixWorld.clone(),control});
  o.geometry.addEventListener('dispose',()=>originalDisposals++);
});
const before=inputs.getState(),mode=createDesignMode(root),body=root.getObjectByName('sculpted-lower-shell'),original=body.geometry;
assert.deepEqual(mode.getState(),{enabled:false,solidControls:true});
mode.setEnabled(true);
assert.notEqual(body.geometry,original,'no design geometry');
assert.ok(body.geometry.index.count<original.index.count/3,'design mesh remains excessively dense');
for(const part of parts) {
  assert.ok(part.o.matrixWorld.equals(part.matrix),'design mode changes a posed part');
  if(part.control){assert.equal(part.o.material,part.material);assert.equal(part.o.geometry,part.geometry);assert.equal(part.o.visible,part.visible);}
  else if(part.o.name.startsWith('mark-'))assert.equal(part.o.visible,false,'body legend becomes a stray wire rectangle');
}
const coarse=body.geometry;
mode.setSolidControls(false);
for(const part of parts.filter(p=>p.control&&!p.o.name.startsWith('mark-')))assert.notEqual(part.o.material,part.material,'control fails to enter wire mode');
const knurl=root.getObjectByName('left-stick-knurling');assert.equal(knurl.material.wireframe,true,'instanced stick grip has no wire style');
mode.setSolidControls(true);assert.equal(body.geometry,coarse,'design geometry is rebuilt on each toggle');
for(let i=0;i<3;i++) {
  mode.setEnabled(false);
  for(const part of parts){assert.equal(part.o.geometry,part.geometry);assert.equal(part.o.material,part.material);assert.equal(part.o.visible,part.visible);assert.equal(part.o.castShadow,part.cast);assert.equal(part.o.receiveShadow,part.receive);}
  mode.setEnabled(true).setSolidControls(true);
}
assert.deepEqual(inputs.getState(),before,'render settings change held inputs');
const triggerPose=root.getObjectByName('L2-joint').matrixWorld.clone();
inputs.setButton('L2',1);assert.ok(!root.getObjectByName('L2-joint').matrixWorld.equals(triggerPose),'input rig stopped updating');
mode.dispose();mode.dispose();assert.equal(originalDisposals,0,'renderer disposed original model assets');
for(const part of parts){assert.equal(part.o.geometry,part.geometry);assert.equal(part.o.material,part.material);}
assert.throws(()=>mode.setEnabled(true));
const source=new THREE.BoxGeometry(4,3,2),sourcePoints=Array.from(source.attributes.position.array),simplified=designGeometry(source);
assert.deepEqual(Array.from(source.attributes.position.array),sourcePoints,'clustering mutates original vertices');
for(const v of simplified.attributes.position.array)assert.ok(Number.isFinite(v));
source.dispose();simplified.dispose();
// A large flat panel has four contour edges, without the distracting diagonal.
const panel=new THREE.Mesh(new THREE.PlaneGeometry(100,60),new THREE.MeshBasicMaterial()),panelRoot=new THREE.Group();panelRoot.add(panel);
const panelMode=createDesignMode(panelRoot);panelMode.setEnabled(true);
const outline=panel.getObjectByName('design-wire-overlay').children[0];
assert.equal(outline.geometry.index.count,24,'flat panel contains internal triangle lines');
panelMode.setViewport(800,600,2);assert.equal(outline.material.uniforms.lineWidth.value,3.6,'outline ignores pixel density');
assert.deepEqual(outline.material.uniforms.resolution.value.toArray(),[1600,1200]);
assert.throws(()=>panelMode.setViewport(0,600));
panelMode.dispose();panel.geometry.dispose();panel.material.dispose();
const thin=new THREE.BoxGeometry(100,60,.4),thinDisplay=designGeometry(thin);
assert.deepEqual(Array.from(thinDisplay.attributes.position.array),Array.from(thin.attributes.position.array),'thin panels get collapsed into stray boundary edges');
thinDisplay.dispose();thin.dispose();
console.log('Design checks passed: quiet panel contours, independent original controls, instancing, held poses, cache reuse, exact material/geometry restoration and disposal.');
