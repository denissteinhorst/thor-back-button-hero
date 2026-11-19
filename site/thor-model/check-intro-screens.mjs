// SPDX-License-Identifier: 0BSD
import {registerHooks} from 'node:module';
import assert from 'node:assert/strict';
registerHooks({resolve(s,c,next){return next(s==='three'?new URL('./vendor/three.module.js',import.meta.url).href:s,c);}});
const THREE=await import('three');
const {createThor}=await import('./thor.js');
const {createIntroScreens}=await import('./intro-screens.js');
const {sampleIntroStory:sampleIntro}=await import('./intro-timeline.js');

const texts=[];
let decoded=0;
globalThis.document={createElement(name){
  assert.equal(name,'canvas');
  const context={draws:0,clearRect(){},drawImage(){this.draws++;},fillRect(){},save(){},restore(){},beginPath(){},arc(){},rect(){},moveTo(){},quadraticCurveTo(){},stroke(){},clip(){},
    getImageData(){return {data:new Uint8ClampedArray(960*836*4)};},putImageData(){},fillText(value){this.draws++;texts.push(value);}};
  return {width:0,height:0,getContext:()=>context};
}};
globalThis.Image=class {
  async decode(){decoded++;}
  set src(value){this.width=value.includes('intro-nature')?1536:512;this.height=value.includes('intro-nature')?1024:512;queueMicrotask(()=>this.onload());}
};
const thor=createThor({markTexture:()=>null}),scene=new THREE.Scene();
scene.add(thor.root);
const originals=[];thor.root.traverse(o=>{if(o.isMesh)originals.push({o,geometry:o.geometry,material:o.material});});
const prepared=[];
const screens=await createIntroScreens(thor,{initTexture(map){assert.ok(map.image.getContext('2d').draws>0,'blank texture was uploaded before painting');prepared.push(map);}});
assert.equal(decoded,2,'assets must decode before the intro becomes available');
assert.equal(prepared.length,4,'textures must upload before playback');
const upper=thor.root.getObjectByName('upper-active-display').children[0];
const lower=thor.root.getObjectByName('lower-active-display').children[0];
const title=thor.hinge.getObjectByName('intro-title');
const watermark=thor.root.getObjectByName('intro-wire-watermark');
assert.equal(watermark.parent,thor.root.getObjectByName('upper-active-display'));
assert.equal(watermark.material.uniforms.opacity.value,.2);
assert.ok(Math.abs(watermark.geometry.parameters.width/132.83-.28)<1e-12);
assert.equal(watermark.visible,false);
screens.setWireWatermarkVisible(true);assert.equal(watermark.visible,true);
screens.setWireWatermarkVisible(false);
assert.equal(title.parent,thor.hinge,'outer-lid title must follow the hinge');
assert.equal(title.material.depthTest,true,'outer-lid title must respect physical occlusion');
assert.equal(title.material.side,THREE.FrontSide,'outer-lid title must not show through from behind');
assert.ok(title.position.z<-4.3,'title must sit outside the closed lid shell');
assert.equal(upper.parent.parent,thor.hinge,'upper display content must follow the actual lid joint');
assert.equal(lower.parent.parent,thor.root,'lower content must remain on the fixed base');
for(const mesh of [upper,lower]){
  const uv=mesh.geometry.getAttribute('uv');
  for(let i=0;i<uv.count;i++){assert.ok(uv.getX(i)>=0&&uv.getX(i)<=1);assert.ok(uv.getY(i)>=0&&uv.getY(i)<=1);}
  assert.equal(mesh.material.depthWrite,true);assert.equal(mesh.material.toneMapped,false);
  assert.equal(mesh.material.transparent,false,'LCD fade must not introduce a competing transparent layer');
  assert.equal(mesh.material.polygonOffset,true);assert.ok(mesh.material.polygonOffsetUnits<0);
  assert.equal(mesh.material.map.generateMipmaps,false);
}
screens.update(sampleIntro(0),true);
assert.equal(title.material.opacity,1);assert.equal(upper.visible,false);assert.equal(lower.visible,false);
assert.equal(upper.material.color.r,0);assert.equal(lower.material.color.r,0);
screens.update(sampleIntro(3),true);
assert.equal(title.material.opacity,1);assert.equal(upper.material.color.r,1);assert.equal(lower.material.color.r,1);
const version=upper.material.map.version;
screens.update(sampleIntro(3),true);assert.equal(upper.material.map.version,version,'paused frame keeps uploading textures');
screens.update(sampleIntro(2),true);assert.ok(upper.material.map.version>version,'scrubbing does not reconstruct the background');
for(const text of ['Back Button Hero 2.0','For AYN Thor *all models','Back Button Hero','Version 2.0 - Feature preview'])assert.ok(texts.includes(text));
for(const angle of [0,60,100,150]){
  thor.setAngle(angle);
  assert.ok(upper.getWorldPosition(new THREE.Vector3()).distanceTo(lower.getWorldPosition(new THREE.Vector3()))>0);
}
for(const [time,frontFacing] of [[0,true],[3,false]]){
  const state=sampleIntro(time);thor.setAngle(state.hingeAngle);
  const normal=new THREE.Vector3(0,0,1).applyQuaternion(title.getWorldQuaternion(new THREE.Quaternion()));
  const viewingDirection=new THREE.Vector3(...state.cameraPosition).sub(title.getWorldPosition(new THREE.Vector3()));
  assert.equal(normal.dot(viewingDirection)>0,frontFacing,'title does not rotate behind the opening lid');
}
screens.update(null,false);
for(const mesh of [upper,lower,title])assert.equal(mesh.visible,false,'intro content remains visible in modelling mode');
for(const {o,geometry,material} of originals){assert.equal(o.geometry,geometry);assert.equal(o.material,material);}
console.log('Intro screen checks passed: decoded/prepared textures, opaque depth-biased LCD fades, paused texture caching, lid/base attachment, UVs, model materials, captions and modelling-mode hiding.');
