// SPDX-License-Identifier: 0BSD
import { registerHooks } from 'node:module';
import assert from 'node:assert/strict';
registerHooks({resolve(specifier,context,next){return next(specifier==='three'?new URL('./vendor/three.module.js',import.meta.url).href:specifier,context);}});
const THREE=await import('three');
const {createThor}=await import('./thor.js');
const {BUTTON_NAMES}=await import('./input-rig.js');
const {root,inputs}=createThor({markTexture:()=>null});
const get=name=>{const o=root.getObjectByName(name);assert.ok(o,name+' missing');return o;};
const world=(object,point=new THREE.Vector3())=>object.localToWorld(point.clone());
const close=(a,b,message)=>assert.ok(Math.abs(a-b)<1e-8,message);
const jointFor=name=>get((name.startsWith('dpad')?'dpad':name.startsWith('volume')?'volume':name==='L3'?'leftStick':name==='R3'?'rightStick':name)+'-joint');
const rests=new Map();root.traverse(o=>rests.set(o,o.matrixWorld.clone()));

// Every exposed input produces a non-neutral physical pose and returns exactly.
for(const name of BUTTON_NAMES) {
  const joint=jointFor(name);
  inputs.setButton(name,.5);const half=joint.matrixWorld.clone();
  assert.ok(!half.equals(rests.get(joint)),name+' has no half-pressure pose');
  inputs.setButton(name,1);assert.ok(!joint.matrixWorld.equals(half),name+' has no full-pressure pose');
  joint.traverse(part=>{
    if(!part.name.startsWith('mark-'))return;
    const normal=new THREE.Vector3(0,0,1).transformDirection(part.matrixWorld);
    const ray=new THREE.Raycaster(world(part).addScaledVector(normal,2),normal.negate());
    const hits=ray.intersectObject(root,true).filter(h=>!h.object.name.startsWith('mark-'));
    assert.ok(hits.length&&hits[0].distance>=1.98,name+': pressed cap legend is occluded');
  });
  inputs.setButton(name,0);
  assert.ok(joint.matrixWorld.equals(rests.get(joint)),name+' fails to return to rest');
}

// The owner's video shows a rocker about an internal fulcrum: the lower finger
// edge enters the housing while the opposite edge moves back. End-mounted pivots
// can satisfy a one-point direction check while levering the whole cap out.
for(const name of ['L2','R2']) {
  const joint=jointFor(name),pivot=world(joint);
  const upper=new THREE.Vector3(0,4.4,-2.05),lower=new THREE.Vector3(0,-5.3,-2.05);
  const restUpper=world(joint,upper),restLower=world(joint,lower);
  let previousUpper=restUpper,previousLower=restLower;
  const other=jointFor(name==='L2'?'R2':'L2'),otherRest=other.matrixWorld.clone();
  for(const pressure of [.1,.25,.5,.75,1]) {
    inputs.setButton(name,pressure);const movedUpper=world(joint,upper),movedLower=world(joint,lower);
    assert.ok(movedUpper.z<previousUpper.z,'upper trigger edge does not counter-rock');
    assert.ok(movedLower.z>previousLower.z,'finger edge fails to enter the housing');
    assert.ok(movedUpper.y<=restUpper.y&&movedLower.y<=restLower.y,'paddle levers upward out of the housing');
    close(world(joint).distanceTo(pivot),0,'trigger hinge translates');
    close(movedUpper.distanceTo(movedLower),restUpper.distanceTo(restLower),'trigger paddle deforms');
    assert.ok(other.matrixWorld.equals(otherRest),'trigger moves its opposite side');
    previousUpper=movedUpper;previousLower=movedLower;
  }
  assert.ok(restUpper.z-previousUpper.z>1,'opposite edge has no visible counter-motion');
  assert.ok(previousLower.z-restLower.z>1.5,'finger edge has no visible inward travel');
  const centre=world(joint,new THREE.Vector3(0,0,-2.05));
  assert.ok(Math.abs(centre.z-(pivot.z-2.05))<.15,'trigger cap centre is ejected rearward');
  inputs.releaseAll();assert.ok(joint.matrixWorld.equals(rests.get(joint)),'trigger fails to release');
}

// AYN follows the inclined platform normal, rather than clipping vertically
// through a stationary flat seat. Its frame remains fixed while the cap moves.
const ayn=get('ayn-button'),aynRest=world(ayn),frame=get('ayn-button-seam').matrixWorld.clone();
const aynNormal=new THREE.Vector3(0,1,0).transformDirection(get('inclined-ayn-switch-mount').matrixWorld);
assert.ok(aynNormal.z>.03&&aynNormal.z<.1,'AYN switch does not follow the slight platform incline');
inputs.setButton('AYN',1);
const aynTravel=world(ayn).sub(aynRest);
close(aynTravel.dot(aynNormal),-.28,'AYN travel is not into its inclined face');
assert.ok(aynTravel.clone().cross(aynNormal).length()<1e-8,'AYN cap slides sideways across platform');
assert.ok(get('ayn-button-seam').matrixWorld.equals(frame),'AYN press moves its fixed surround');
inputs.releaseAll();

// The cross is a single connected, closed solid, not four independently moving caps.
const g=get('dpad-rocker').geometry, index=g.index.array, neighbours=new Map(),edges=new Map();
for(let i=0;i<index.length;i+=3)for(const [a,b] of [[index[i],index[i+1]],[index[i+1],index[i+2]],[index[i+2],index[i]]]) {
  if(!neighbours.has(a))neighbours.set(a,new Set());if(!neighbours.has(b))neighbours.set(b,new Set());
  neighbours.get(a).add(b);neighbours.get(b).add(a);
  const key=a<b?a+':'+b:b+':'+a;edges.set(key,(edges.get(key)||0)+1);
}
for(const count of edges.values())assert.equal(count,2,'open D-pad edge');
const seen=new Set(),queue=[index[0]];
while(queue.length){const p=queue.pop();if(seen.has(p))continue;seen.add(p);for(const n of neighbours.get(p))if(!seen.has(n))queue.push(n);}
assert.equal(seen.size,neighbours.size,'D-pad consists of disconnected pieces');
const dp=get('dpad-joint');
const points={up:new THREE.Vector3(0,1.5,-8),down:new THREE.Vector3(0,1.5,8),left:new THREE.Vector3(-8,1.5,0),right:new THREE.Vector3(8,1.5,0)};
const neutral=Object.fromEntries(Object.entries(points).map(([k,v])=>[k,world(dp,v)]));
for(const [name,x,y,opposite] of [['up',0,1,'down'],['down',0,-1,'up'],['right',1,0,'left'],['left',-1,0,'right']]) {
  inputs.setDpad(x,y);
  assert.ok(world(dp,points[name]).y<neutral[name].y,'pressed D-pad edge fails to descend');
  assert.ok(world(dp,points[opposite]).y>neutral[opposite].y,'opposite D-pad edge fails to lift');
  close(world(dp,points.up).distanceTo(world(dp,points.right)),neutral.up.distanceTo(neutral.right),'rocker deforms');
}
inputs.setButton('dpadRight',1).setButton('dpadUp',1);
close(Math.hypot(inputs.getState().dpad.x,inputs.getState().dpad.y),1,'D-pad diagonal exceeds circular limit');
inputs.releaseAll();inputs.setButton('dpadUp',1).setButton('dpadDown',1);
assert.ok(dp.quaternion.equals(new THREE.Quaternion()),'opposite D-pad directions fail to cancel');
inputs.releaseAll();

// Both sticks tilt in all four cardinal directions, with independent axial clicks.
for(const side of ['left','right']) {
  const prefix=side==='left'?'left-stick':'right-stick',click=side==='left'?'L3':'R3';
  const crown=get(prefix+'-rubber-cap'),centre=world(crown);
  for(const [x,y] of [[1,0],[-1,0],[0,1],[0,-1]]) {
    inputs.setStick(side,{x,y});const tilted=world(crown);
    if(x)assert.ok((tilted.x-centre.x)*x>1,'stick fails to lean horizontally');
    if(y)assert.ok((tilted.z-centre.z)*y< -1,'stick fails to lean vertically');
    const orientation=crown.getWorldQuaternion(new THREE.Quaternion());
    inputs.setButton(click,1);const clicked=world(crown);
    close(clicked.y,tilted.y-.45,'stick click fails to descend');
    close(clicked.x,tilted.x,'click changes stick x');close(clicked.z,tilted.z,'click changes stick z');
    assert.ok(crown.getWorldQuaternion(new THREE.Quaternion()).equals(orientation),'click loses the tilt');
    inputs.setButton(click,0);inputs.setStick(side,{x:0,y:0});
  }
  inputs.setStick(side,{x:1,y:1,press:.6});
  const state=inputs.getState().sticks[side];close(Math.hypot(state.x,state.y),1,'stick diagonal limit');close(state.press,.6);
  assert.equal(inputs.getState().buttons[click],.6,'stick press and L3/R3 diverge');inputs.releaseAll();
}
inputs.setStick('left',{x:.8,y:.4,press:1});
assert.deepEqual(inputs.getState().sticks.right,{x:0,y:0,press:0},'left stick moves right stick');
inputs.setStick('left',{press:0});assert.equal(inputs.getState().sticks.left.x,.8,'releasing L3 resets tilt');
inputs.releaseAll();

// A rocker is shared by volume +/-; the engraved marks move with their caps.
const volume=get('volume-joint');
inputs.setButton('volumeUp',1);assert.ok(volume.rotation.y>0);
inputs.releaseAll().setButton('volumeDown',1);assert.ok(volume.rotation.y<0);
inputs.releaseAll().setButton('volumeDown',1).setButton('volumeUp',1);close(volume.rotation.y,0);
inputs.releaseAll();
for(const name of ['A','B','X','Y','AYN','select','start','home','back','power','L1','L2','R1','R2']) {
  const legendName=['select','start','home','back','power'].includes(name)?name:name;
  const legend=get('mark-'+legendName);assert.equal(legend.parent,jointFor(name),'legend detached from cap');
}

// No drift or mutation of fixed glass, collars, shell, wells or neutral geometry.
for(let i=0;i<12;i++) {
  for(const name of BUTTON_NAMES)inputs.setButton(name,i%2?.4:1);
  inputs.setStick('left',{x:-.7,y:.7,press:1});inputs.setStick('right',{x:.7,y:-.7,press:1});inputs.releaseAll();
}
root.traverse(o=>assert.ok(o.matrixWorld.equals(rests.get(o)),o.name+' drifts after releaseAll'));

// Reject invalid inputs atomically and keep state snapshots separate from the rig.
const before=inputs.getState();
for(const action of [()=>inputs.setButton('unknown',1),()=>inputs.setButton('A',NaN),()=>inputs.setDpad(1,NaN),()=>inputs.setStick('unknown',{x:1}),()=>inputs.setStick('left',{x:1,press:NaN})]) {
  assert.throws(action);assert.deepEqual(inputs.getState(),before,'invalid input partly changes pose');
}
const snapshot=inputs.getState();snapshot.buttons.A=1;snapshot.sticks.left.x=1;
assert.deepEqual(inputs.getState(),before,'state getter exposes mutable internals');
let notifications=0;const unsubscribe=inputs.subscribe(()=>notifications++);
inputs.setButton('A',1);assert.equal(notifications,2);unsubscribe();inputs.releaseAll();assert.equal(notifications,2);
console.log(`Input checks passed: ${BUTTON_NAMES.length} button channels; one connected D-pad; cardinal/diagonal tilts, independent L3/R3, labels, cancellation, release and invalid-input handling.`);
