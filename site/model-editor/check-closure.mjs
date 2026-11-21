// SPDX-License-Identifier: 0BSD
// Check junctions between parts: closed individual meshes alone do not prove
// that an assembled case is opaque, especially along grazing viewing angles.
import {registerHooks} from 'node:module';
import assert from 'node:assert/strict';
registerHooks({resolve(specifier,context,next){return next(specifier==='three'?new URL('./vendor/three.module.js',import.meta.url).href:specifier,context);}});
const THREE=await import('three');
const {createThor}=await import('./thor.js');
const {roundedOutline,undersideRelief}=await import('./geometry.js');
const {root,inputs}=createThor({markTexture:()=>null});
const get=name=>{const o=root.getObjectByName(name);assert.ok(o,name+' missing');return o;};
const walls=['sculpted-lower-shell','perforated-front-fascia','shell-deck-closure','glass-perimeter-lip'].map(get);
const cast=(origin,direction,objects=walls)=>new THREE.Raycaster(new THREE.Vector3(...origin),new THREE.Vector3(...direction).normalize()).intersectObjects(objects);
let probes=0;

// The former fascia stopped below the deck, leaving a roughly 1 mm open strip.
for(let x=-64;x<=64;x+=4)for(const y of [6.15,6.5,6.95])for(const slope of [-.03,0,.03]) {
  const hits=cast([x,y,50],[0,slope,-1]);probes++;
  assert.ok(hits.length&&hits[0].point.z>46,'open strip above front fascia at '+x+','+y);
}
// A top-down ray through the narrow outside-of-glass strip must hit a shell
// flange. The old wall/lip footprints left this strip completely uncovered.
for(const side of [-1,1])for(let z=-30;z<=35;z+=5)for(const x of [74.6,74.7,74.8]) {
  const hits=cast([side*x,20,z],[0,-1,0]);probes++;
  assert.ok(hits.length&&hits[0].point.y>7,'open glass-to-wall junction at '+side*x+','+z);
}
// Probe the actual curved lower perimeter, including the raised rear cheeks.
const edge=roundedOutline(141.6,85.6,6.8).getPoints(12).slice(0,-1);
for(const p of edge)for(const slope of [-.08,0,.08]) {
  const z=-p.y,y=-10.4+undersideRelief(p.x,-10.4,z),normal=new THREE.Vector3(p.x,0,z).normalize();
  // Start beyond the flared sidewall even at upward-sloping ray origins.
  const hits=cast([p.x+normal.x*8,y-slope*8,z+normal.z*8],[-normal.x,slope,-normal.z]);probes++;
  assert.ok(hits.length&&hits[0].distance<8.1,'open underside-to-wall junction at '+p.x+','+z);
}

// Both integrated cheek domes rise out of the broad underside and taper
// forward. Their height must be visible in actual intersections, not a decal.
const bottom=get('sculpted-lower-shell');
const bodyIndex=bottom.geometry.index.array,bodyPosition=bottom.geometry.attributes.position,edges=new Map();
for(let i=0;i<bodyIndex.length;i+=3)for(const [a,b]of [[bodyIndex[i],bodyIndex[i+1]],[bodyIndex[i+1],bodyIndex[i+2]],[bodyIndex[i+2],bodyIndex[i]]]) {
  const key=a<b?a+':'+b:b+':'+a;edges.set(key,(edges.get(key)||0)+1);
}
for(const [key,count]of edges) {
  const [a,b]=key.split(':').map(Number);
  if([a,b].every(i=>Math.abs(bodyPosition.getX(i))>47&&bodyPosition.getZ(i)<-22&&bodyPosition.getY(i)<-5)) {
    assert.equal(count,2,'open/T-junction edge in the integrated shoulder dome');
  }
}
const height=(x,z)=>{const h=cast([x,-30,z],[0,1,0],[bottom]);assert.ok(h.length,'underside opening');return h[0].point.y;};
const flat=height(0,0);
for(const side of [-1,1]) {
  const peak=height(side*64,-39),slope=height(side*60,-25);
  assert.ok(flat-peak>4.8,'shoulder dome is too flat');
  assert.ok(slope>peak+.8&&slope<flat-.3,'shoulder dome has no rounded forward transition');
}

for(const side of ['L','R']) {
  const cap=get(side+'2-trigger'),material=cap.material,previousSide=material.side;
  material.side=THREE.DoubleSide;
  const x=side==='L'?-64:64;
  const lip=cast([x+8.5,-4.2,-65],[0,0,1],[cap]);
  const stem=cast([x,-4.2,-65],[0,0,1],[cap]);
  assert.ok(lip.length>=2&&stem.length>=2,'trigger lip or moving stem is open');
  const lipDepth=lip.at(-1).point.z-lip[0].point.z,stemDepth=stem.at(-1).point.z-stem[0].point.z;
  assert.ok(lipDepth>.5&&lipDepth<1.2,'trigger contact lip is still a full-depth block');
  assert.ok(stemDepth>5,'moving trigger stem is missing');material.side=previousSide;
  const joint=get(side+'2-joint'),legend=get('mark-'+side+'2');
  for(const pressure of [0,.25,.5,.75,1]) {
    inputs.setButton(side+'2',pressure);
    const centre=legend.getWorldPosition(new THREE.Vector3()),normal=new THREE.Vector3(0,0,1).transformDirection(legend.matrixWorld);
    const hits=new THREE.Raycaster(centre.clone().addScaledVector(normal,2),normal.negate()).intersectObject(root,true).filter(h=>!h.object.name.startsWith('mark-'));
    assert.ok(hits.length&&hits[0].distance>=1.98,'trigger legend passes through its housing');
    assert.ok(joint.position.y>-5&&joint.position.y<-3,'fulcrum is at an end of the paddle');
  }
  inputs.releaseAll();
}
console.log(`Closure checks passed: ${probes} fascia, glass-rim and underside probes; integrated shoulder domes, thin paddles, moving stems and pressure sweep.`);
