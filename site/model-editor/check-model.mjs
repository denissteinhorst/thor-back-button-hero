// SPDX-License-Identifier: 0BSD
// Structural checks: no WebGL, canvas, test dependency, or change to the Android suite.
import { registerHooks } from 'node:module';
import assert from 'node:assert/strict';
registerHooks({resolve(specifier,context,next){
  return next(specifier==='three'?new URL('./vendor/three.module.js',import.meta.url).href:specifier,context);
}});
const THREE=await import('three');
const {createThor,LAYOUT}=await import('./thor.js');
const {updateStudioKey}=await import('./lighting.js');
const {shoulderRecess}=await import('./geometry.js');
const {root,hinge,setAngle}=createThor({markTexture:()=>null});
let meshes=0,triangles=0;
root.traverse(o=>{
  if(!o.isMesh)return;meshes++;
  const p=o.geometry.attributes.position;
  for(const value of p.array)assert.ok(Number.isFinite(value),`${o.name}: non-finite vertex`);
  const index=o.geometry.index;
  if(index)for(const i of index.array)assert.ok(i<p.count,`${o.name}: invalid index`);
  triangles+=(index?index.count:p.count)/3;
});
const get=name=>{const o=root.getObjectByName(name);assert.ok(o,`missing ${name}`);return o;};
const shellBounds=new THREE.Box3().setFromObject(get('sculpted-lower-shell'));
assert.ok(Math.abs(shellBounds.max.x-shellBounds.min.x-150)<.01);
assert.ok(Math.abs(shellBounds.max.z-shellBounds.min.z-94)<.01);
// Stick and D-pad heights must clear the closed glass; the old caps intersected it.
setAngle(0);
const lidBottom=new THREE.Box3().setFromObject(get('upper-full-glass')).min.y;
for(const name of ['left-stick-rubber-cap','right-stick-rubber-cap','button-A','button-X','home-button']) {
  assert.ok(new THREE.Box3().setFromObject(get(name)).max.y<lidBottom,`${name}: closed lid collision`);
}
// Contact pads must actually meet the closed glass, while the AYN switch clears it.
for(const side of ['left','right']) {
  const stop=get(side+'-lid-stop'), bounds=new THREE.Box3().setFromObject(stop);
  const ray=new THREE.Raycaster(new THREE.Vector3(stop.position.x,bounds.max.y-.01,stop.position.z),new THREE.Vector3(0,1,0));
  const hits=ray.intersectObject(get('upper-full-glass'));
  assert.ok(hits.length>0,'lid stop misses the upper bezel');
  assert.ok(Math.abs(hits[0].point.y-bounds.max.y)<.025,'lid stop does not contact closed glass');
}
assert.ok(new THREE.Box3().setFromObject(get('ayn-button')).max.y<lidBottom,'AYN switch is pressed by the closed lid');
assert.ok(get('ayn-button').material.color.equals(get('left-stick-stem').material.color),'AYN cap must share the dark stick plastic');
const bridge=get('raised-ayn-bridge');
const bridgeHeight=(x,z)=>{
  const hits=new THREE.Raycaster(new THREE.Vector3(x,20,z),new THREE.Vector3(0,-1,0)).intersectObject(bridge);
  assert.ok(hits.length,'bridge surface is open or inward-facing');return hits[0].point.y;
};
assert.ok(bridgeHeight(20,42.4)-bridgeHeight(20,45)>.1,'bridge top has no forward incline');
assert.ok(bridgeHeight(25,43.8)-bridgeHeight(29,43.8)>1,'bridge side is a vertical block without a broad ramp');
for(const side of ['left','right']) {
  const stop=get(side+'-lid-stop-seat'),base=new THREE.Box3().setFromObject(stop).min.y;
  assert.ok(Math.abs(base-bridgeHeight(stop.position.x,stop.position.z))<.03,'lid stop seat floats above its bridge');
}
const bridgeEdges=new Map(),bridgeIndex=bridge.geometry.index.array;
for(let i=0;i<bridgeIndex.length;i+=3)for(const [a,b]of [[bridgeIndex[i],bridgeIndex[i+1]],[bridgeIndex[i+1],bridgeIndex[i+2]],[bridgeIndex[i+2],bridgeIndex[i]]]) {
  const key=a<b?a+':'+b:b+':'+a;bridgeEdges.set(key,(bridgeEdges.get(key)||0)+1);
}
for(const count of bridgeEdges.values())assert.equal(count,2,'bridge has an open or non-manifold edge');
// Rear-facing rays must reach the shoulder caps before any original shell wall.
for(const side of [-1,1])for(const [suffix,y] of [['1-bumper',2.7],['2-trigger',-4]]) {
  const prefix=side<0?'L':'R';
  const ray=new THREE.Raycaster(new THREE.Vector3(side*64,y,-65),new THREE.Vector3(0,0,1));
  const hits=ray.intersectObject(root,true).filter(h=>!h.object.name.startsWith('mark-'));
  assert.equal(hits[0]?.object.name,prefix+suffix,'shoulder cap is hidden behind another surface');
}
// A few centre rays missed the cracks beside the old pockets. Sweep the entire
// rear shoulder backing, and require an actual rear-wall hit before the far side.
for(const side of [-1,1])for(let x=51;x<=71;x+=.5)for(let y=-8;y<=5;y+=.5) {
  // Keep the probe inside the same moulded wall region after reshaping the
  // underside; the old world y=-8 is now outside the thin central shell.
  const sample=shoulderRecess(side*x,y,-46);
  const ray=new THREE.Raycaster(new THREE.Vector3(sample.x,sample.y,-65),new THREE.Vector3(0,0,1));
  const hits=ray.intersectObject(get('sculpted-lower-shell'));
  assert.ok(hits.length>0 && hits[0].point.z< -28,`open shoulder backing at ${side*x},${y}`);
}
// The join must follow the deformed case contour, not protrude through a cap
// as a fin. Compare actual surface intersections rather than only mesh closure.
for(const side of [-1,1])for(let x=50;x<=73;x+=.25) {
  const ray=new THREE.Raycaster(new THREE.Vector3(side*x,-4.9,-65),new THREE.Vector3(0,0,1));
  const wall=ray.intersectObject(get('sculpted-lower-shell'));
  const join=ray.intersectObject(get('shell-joining-line'));
  assert.ok(wall.length&&join.length,'case join or wall missing');
  assert.ok(Math.abs(wall[0].point.z-join[0].point.z)<.2,'case join protrudes from recessed shoulder wall');
}
// The skirts must extend into the lateral housing, with no free-standing gap.
for(const side of [-1,1])for(const [suffix,y] of [['1-bumper',2.7],['2-trigger',-4]]) {
  const control=get((side<0?'L':'R')+suffix), material=control.material, oldSide=material.side;
  material.side=THREE.DoubleSide;
  for(const x of [71,72,73]) {
    const ray=new THREE.Raycaster(new THREE.Vector3(side*x,y,-65),new THREE.Vector3(0,0,1));
    const cap=ray.intersectObject(control),wall=ray.intersectObject(get('sculpted-lower-shell'));
    assert.ok(cap.length>=2&&wall.length,'missing shoulder skirt');
    assert.ok(cap.at(-1).point.z>=wall[0].point.z-.15,'shoulder skirt stops short of sidewall');
  }
  material.side=oldSide;
}
// Closed connected solids: every undirected edge has exactly two incident
// triangles, including the shared subdivisions along cap/fillet boundaries.
for(const side of ['L','R'])for(const suffix of ['1-bumper','2-trigger','-shoulder-pocket']) {
  const g=get(side+suffix).geometry, index=g.index.array, edges=new Map();
  const add=(a,b)=>{const key=a<b?a+':'+b:b+':'+a;edges.set(key,(edges.get(key)||0)+1);};
  for(let i=0;i<index.length;i+=3){add(index[i],index[i+1]);add(index[i+1],index[i+2]);add(index[i+2],index[i]);}
  for(const count of edges.values())assert.equal(count,2,side+suffix+': open or non-manifold cap edge');
}
const dpadColour=get('dpad-seat').material.color;
const faceColour=get('face-button-circular-inlay').material.color;
assert.ok(dpadColour.equals(faceColour),'D-pad seat must share the light ABXY inlay material');
// No open rear-strip slit between the deck and the hinge, including near its ends.
for(const x of [-68,-40,0,40,68]) {
  const ray=new THREE.Raycaster(new THREE.Vector3(x,20,-35.5),new THREE.Vector3(0,-1,0));
  assert.ok(ray.intersectObject(get('closed-hinge-saddle')).length>0,'uncovered rear shell strip');
}
// Hinge outer noses taper to a rounded end, rather than retaining a full-radius end cap.
for(const side of [-1,1]) {
  const cap=get(side<0?'left-rounded-hinge-cap':'right-rounded-hinge-cap');
  const noseRay=new THREE.Raycaster(new THREE.Vector3(side*74.5,30,-41),new THREE.Vector3(0,-1,0));
  const hits=noseRay.intersectObject(cap);
  assert.ok(hits.length>0 && hits[0].point.y<13.2,'outer hinge cap has a flat cylindrical end');
}
// A label drawn behind its cap can pass syntax checks while remaining invisible.
setAngle(135);
root.traverse(o=>{
  if(!o.name.startsWith('mark-'))return;
  const centre=o.getWorldPosition(new THREE.Vector3());
  const normal=new THREE.Vector3(0,0,1).transformDirection(o.matrixWorld);
  const ray=new THREE.Raycaster(centre.clone().addScaledVector(normal,2),normal.clone().negate());
  const hits=ray.intersectObject(root,true).filter(h=>!h.object.name.startsWith('mark-'));
  assert.ok(hits.length>0 && hits[0].distance>=1.995,`${o.name}: legend hidden behind geometry`);
});
for(const [p,name] of [[LAYOUT.leftStick,'left-stick'],[LAYOUT.rightStick,'right-stick']]) {
  const capRay=new THREE.Raycaster(new THREE.Vector3(p.x,30,p.z),new THREE.Vector3(0,-1,0));
  assert.ok(capRay.intersectObject(get(name+'-rubber-cap')).length>0,'cap normals face inward');
  const bowlRay=new THREE.Raycaster(new THREE.Vector3(p.x+10,30,p.z),new THREE.Vector3(0,-1,0));
  const hits=bowlRay.intersectObject(get(name+'-recess'));
  assert.ok(hits.length>0 && hits[0].point.y<7,'stick recess normals or profile are wrong');
}
// Geometrically project the cap/stem shadow onto the well floor from opposite
// inspection angles. Changing only the camera must change the visible footprint.
const key=new THREE.DirectionalLight(), inspectionCamera=new THREE.PerspectiveCamera();
const lightTarget=new THREE.Vector3(0,29,-20), stick=LAYOUT.leftStick;
const shadowPattern=position=>{
  inspectionCamera.position.set(...position);inspectionCamera.lookAt(lightTarget);inspectionCamera.updateMatrixWorld();
  updateStudioKey(key,inspectionCamera,lightTarget);
  const direction=key.position.clone().sub(lightTarget).normalize();
  const pattern=[];
  for(let x=-7;x<=7;x+=.5)for(let z=-7;z<=7;z+=.5) {
    if(x*x+z*z>49)continue;
    const ray=new THREE.Raycaster(new THREE.Vector3(stick.x+x,3.7,stick.z+z),direction,.05,100);
    pattern.push(ray.intersectObjects([get('left-stick-rubber-cap'),get('left-stick-stem')]).length>0);
  }
  return pattern;
};
const frontShadow=shadowPattern([0,180,260]), rearShadow=shadowPattern([0,180,-260]);
assert.ok(frontShadow.some(Boolean)&&rearShadow.some(Boolean),'stick does not cast a projected shadow');
assert.notDeepEqual(frontShadow,rearShadow,'stick shadow is fixed to the model while orbiting');
assert.ok(get('left-stick-rubber-cap').castShadow&&get('left-stick-well-floor').receiveShadow);
const pivot=hinge.position.clone();
for(const a of [0,45,90,135,180]){setAngle(a);assert.ok(hinge.position.equals(pivot));}
assert.throws(()=>setAngle(-1),RangeError);assert.throws(()=>setAngle(181),RangeError);assert.throws(()=>setAngle(NaN),RangeError);
// Rays through the glass must not hit a cap across the stick wells or the notch.
const glass=get('one-piece-glass-control-deck');
for(const p of [LAYOUT.leftStick,LAYOUT.rightStick]) {
  const ray=new THREE.Raycaster(new THREE.Vector3(p.x+9,30,p.z),new THREE.Vector3(0,-1,0));
  assert.equal(ray.intersectObject(glass).length,0,'stick opening was filled');
}
const notchRay=new THREE.Raycaster(new THREE.Vector3(0,30,44),new THREE.Vector3(0,-1,0));
assert.equal(notchRay.intersectObject(glass).length,0,'glass notch was filled');
for(const [x,z] of [[-70,0],[70,0],[0,-30],[0,39]]) {
  const ray=new THREE.Raycaster(new THREE.Vector3(x,30,z),new THREE.Vector3(0,-1,0));
  assert.ok(ray.intersectObject(glass).length>0,'glass triangulation left a hole');
}
// The USB opening must remain an aperture in the fascia, not a black decal on a wall.
const usbRay=new THREE.Raycaster(new THREE.Vector3(7.3,-.5,60),new THREE.Vector3(0,0,-1));
assert.equal(usbRay.intersectObject(get('perforated-front-fascia')).length,0);
console.log(`Model checks passed: ${meshes} meshes, ${Math.round(triangles)} base triangles; caps closed, shoulder backing continuous, lid clearance verified; ${frontShadow.filter((v,i)=>v!==rearShadow[i]).length} stick-shadow samples change with viewpoint.`);
