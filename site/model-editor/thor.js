// SPDX-License-Identifier: 0BSD
// Independent photo-based reconstruction. Units: mm; x right, y up, z toward player.
import * as THREE from 'three';
import { roundedOutline, controlOutline, circularHole, slotHole, capsuleOutline, capsuleHole, extrude, shellWall, bowlGeometry, dpadGeometry, triggerOutline, triggerGeometry, bumperOutline, shoulderGeometry, hingeCheekGeometry, caseJoinGeometry, aynBridgeGeometry, AYN_SLOPE } from './geometry.js';
import { createMarkTexture } from './surfaces.js';
import { createInputRig } from './input-rig.js';

export const DIMENSIONS=Object.freeze({width:150,depth:94,closedHeight:25.6});
// Layout registered against the near-frontal hardware photo, not the old illustration.
export const LAYOUT=Object.freeze({
  leftStick:{x:-56.3,z:-12.6},dpad:{x:-54.8,z:15.5},
  rightStick:{x:57.6,z:16},faceButtons:{x:57.2,z:-12.7},
  select:{x:-45.9,z:-28.6},start:{x:46.4,z:-28.4},
  home:{x:-45,z:35.8},back:{x:46.3,z:35.6}
});

export function createThor({markTexture=createMarkTexture}={}) {
  const root=new THREE.Group();root.name='AYN Thor Rainbow';
  const joints={};
  function joint(name,pivot,parts,parent=root) {
    const group=new THREE.Group();group.name=name+'-joint';group.position.set(...pivot);parent.add(group);
    group.userData.inputControl=name;
    for(const part of parts)group.attach(part);
    joints[name]=group;return group;
  }
  const mat=(color,roughness=.5,metalness=0)=>new THREE.MeshStandardMaterial({color,roughness,metalness});
  const shell=mat('#c8c5b9',.46), lip=mat('#b4b3a8',.44), seam=mat('#777a71',.75);
  const cap=mat('#45494b',.82), rubber=mat('#3c4042',.94), cavity=mat('#555e5f',.62);
  const black=mat('#07090a',.6);
  const controlSeat=mat('#b6b7ac',.42);
  const deckMaterial=new THREE.MeshPhysicalMaterial({color:'#bcbdb2',roughness:.24,metalness:0,clearcoat:1,clearcoatRoughness:.13});
  const glassMaterial=new THREE.MeshPhysicalMaterial({color:'#101417',roughness:.13,clearcoat:1,clearcoatRoughness:.07});
  const oledMaterial=new THREE.MeshPhysicalMaterial({color:'#050708',roughness:.1,clearcoat:1,clearcoatRoughness:.07});
  const led=mat('#9baca8',.42);
  const stickWell=mat('#929a94',.58);
  function mesh(parent,g,m,x=0,y=0,z=0,name='') {
    const o=new THREE.Mesh(g,m);o.position.set(x,y,z);o.name=name;o.castShadow=true;o.receiveShadow=true;parent.add(o);return o;
  }
  function plate(parent,w,h,t,r,m,x=0,y=0,z=0,name='',bevel=.1) {
    return mesh(parent,extrude(roundedOutline(w,h,r),t,bevel),m,x,y,z,name);
  }
  function deckPlate(parent,w,d,t,r,m,x,y,z,name='',bevel=.1) {
    const o=plate(parent,w,d,t,r,m,x,y,z,name,bevel);o.rotation.x=-Math.PI/2;return o;
  }
  function capsule(parent,w,h,t,m,x,y,z,name,bevel=.12) {
    return mesh(parent,extrude(capsuleOutline(w,h),t,bevel),m,x,y,z,name);
  }
  function disk(parent,r,h,m,x,y,z,name='',rTop=r) {
    return mesh(parent,new THREE.CylinderGeometry(rTop,r,h,80),m,x,y,z,name);
  }
  function ring(parent,r,t,m,x,y,z) {
    const o=mesh(parent,new THREE.TorusGeometry(r,t,12,96),m,x,y,z);o.rotation.x=-Math.PI/2;return o;
  }
  function mark(parent,text,x,y,z,w,h,color='#d8d9d1',deck=true) {
    const map=markTexture(text,color);
    const o=mesh(parent,new THREE.PlaneGeometry(w,h),new THREE.MeshBasicMaterial({map,transparent:true,depthWrite:false}),x,y,z,'mark-'+text);
    o.castShadow=false;if(deck)o.rotation.x=-Math.PI/2;return o;
  }
  function screw(x,y,z,up=true,parent=root) {
    const head=disk(parent,1.32,.22,cavity,x,y,z,'screw');
    if(!up)head.rotation.x=Math.PI;
    const cut=deckPlate(parent,1.55,.28,.08,.1,black,x,y+(up?.15:-.15),z,'screw-slot',0);
    if(!up)cut.rotation.x=Math.PI/2;
  }

  const underside=roundedOutline(141.6,85.6,6.8),fan={x:24,z:-13.2,r:13.1};
  for(let row=-6;row<=6;row++) {
    const z=fan.z+row*1.85,span=2*Math.sqrt(fan.r**2-(row*1.85)**2);
    for(const part of [-1,1])slotHole(underside,fan.x+part*(span/4+.45),-z,Math.max(.7,span/2-.9),.9,.4);
  }
  for(const x of [-30,0,30])slotHole(underside,x,34.4,26.6,2.3,1.1);
  const body=mesh(root,shellWall(underside),shell,0,0,0,'sculpted-lower-shell');
  // The shell loft has outward clockwise winding; use a two-sided material for its open rim.
  body.material=body.material.clone();body.material.side=THREE.DoubleSide;
  // Circumferential joining line follows the sidewall, with a separate soft bottom shell.
  mesh(root,caseJoinGeometry(),seam,0,0,0,'shell-joining-line');

  // The vertical shell and inset glass outline have different footprints. A
  // continuous flange closes that junction, including the front bridge notch.
  const deckClosure=roundedOutline(149.7,93.7,7.1);
  deckClosure.holes.push(new THREE.Path(controlOutline(147,80.6,6,-5.6).getPoints(24).reverse()));
  const closure=mesh(root,extrude(deckClosure,.3,0),shell,0,7.3,0,'shell-deck-closure');closure.rotation.x=-Math.PI/2;

  // Top glass: one contiguous shaped sheet with real apertures and a front centre notch.
  const deck=controlOutline();
  for(const p of [LAYOUT.leftStick,LAYOUT.rightStick])circularHole(deck,p.x,-p.z,12.65);
  for(const p of [LAYOUT.select,LAYOUT.start,LAYOUT.home,LAYOUT.back])circularHole(deck,p.x,-p.z,2.25);
  const face=LAYOUT.faceButtons;
  for(const [dx,dz] of [[0,-8.2],[-8.2,0],[8.2,0],[0,8.2]])circularHole(deck,face.x+dx,-face.z-dz,4.03);
  for(const x of [-60.4,60.4])slotHole(deck,x,-37.5,12.4,.85,.42);
  circularHole(deck,-46.2,-3.2,.46);
  const controlDeck=mesh(root,extrude(deck,.42,0),deckMaterial,0,7.58,0,'one-piece-glass-control-deck');
  controlDeck.rotation.x=-Math.PI/2;
  // Narrow case lip around the glass is visible independently of the glass coating.
  const rim=controlOutline(149,82.6,6.8,-5.7);
  rim.holes.push(new THREE.Path(controlOutline(147.4,81,6.1,-5.6).getPoints(24).reverse()));
  const rimMesh=mesh(root,extrude(rim,.45,0),shell,0,7.45,0,'glass-perimeter-lip');rimMesh.rotation.x=-Math.PI/2;
  for(const x of [-60.4,60.4])deckPlate(root,12.6,1.2,.4,.5,black,x,6.8,37.5,'speaker-slot');
  disk(root,.45,.3,black,-46.2,6.9,3.2,'microphone');

  // Active OLED windows are flush under the shared glass; no raised tablet-like frame.
  deckPlate(root,75.15,65.45,.06,.55,oledMaterial,0,7.82,4.7,'lower-active-display',0);

  function stick(p,name) {
    mesh(root,bowlGeometry(),stickWell,p.x,0,p.z,name+'-recess');
    ring(root,12.55,.18,lip,p.x,7.68,p.z);
    disk(root,7.6,.2,stickWell,p.x,3.52,p.z,name+'-well-floor');
    ring(root,7.25,.44,led,p.x,3.76,p.z);
    const stem=disk(root,3.5,4.3,cap,p.x,5.6,p.z,name+'-stem');
    // Concave rubber crown and fine serrated perimeter, not a tall cylindrical peg.
    const profile=[new THREE.Vector2(0,1.05),new THREE.Vector2(3.8,1.08),new THREE.Vector2(4.7,1.24),new THREE.Vector2(5.35,1.55),new THREE.Vector2(5.85,1.5),new THREE.Vector2(6.15,1.1),new THREE.Vector2(6.15,-.3),new THREE.Vector2(5.5,-.8),new THREE.Vector2(0,-.8)];
    const crown=mesh(root,new THREE.LatheGeometry(profile.reverse(),96),rubber,p.x,8,p.z,name+'-rubber-cap');
    const topRing=ring(root,5.18,.12,cap,p.x,9.48,p.z);
    const ribs=new THREE.InstancedMesh(new THREE.BoxGeometry(.12,.9,.18),cap,80);
    const dummy=new THREE.Object3D();
    for(let i=0;i<80;i++){const a=i*Math.PI/40;dummy.position.set(p.x+6.12*Math.cos(a),8.6,p.z+6.12*Math.sin(a));dummy.rotation.y=-a;dummy.updateMatrix();ribs.setMatrixAt(i,dummy.matrix);}
    ribs.name=name+'-knurling';ribs.castShadow=true;root.add(ribs);
    joint(name==='left-stick'?'leftStick':'rightStick',[p.x,4.1,p.z],[stem,crown,topRing,ribs]);
  }
  stick(LAYOUT.leftStick,'left-stick');stick(LAYOUT.rightStick,'right-stick');

  // One connected rocker carries all four directions and their surface grooves.
  const dp=LAYOUT.dpad;
  // The circular inlay stays fixed while the rocker pivots above it.
  disk(root,11.05,.12,lip,dp.x,7.83,dp.z,'dpad-seat-edge');
  disk(root,10.87,.1,controlSeat,dp.x,7.92,dp.z,'dpad-seat');
  const rocker=mesh(root,dpadGeometry(),cap,dp.x,8.45,dp.z,'dpad-rocker');rocker.rotation.x=-Math.PI/2;
  const dpadParts=[rocker];
  for(let i=0;i<4;i++) {
    const group=new THREE.Group();root.add(group);group.position.set(dp.x,8.45,dp.z);group.rotation.y=i*Math.PI/2;
    mark(group,'direction',0,.82,-6.25,2.35,1.9,'#747976');
    dpadParts.push(group);
  }
  joint('dpad',[dp.x,7.3,dp.z],dpadParts);

  disk(root,12.7,.12,controlSeat,face.x,7.82,face.z,'face-button-circular-inlay');
  for(const [text,dx,dz,color] of [
    ['X',0,-8.2,'#183f9d'],['Y',-8.2,0,'#14713a'],['A',8.2,0,'#bb1d2c'],['B',0,8.2,'#d89a0a']
  ]) {
    const x=face.x+dx,z=face.z+dz;
    disk(root,4.0,.4,cavity,x,7.8,z);
    const button=mesh(root,extrude(roundedOutline(7.35,7.35,3.65),1.1,.22),mat(color,.3),x,8.55,z,'button-'+text);
    button.rotation.x=-Math.PI/2;
    const legend=mark(root,text,x,9.36,z,2.45,2.65,'#e4e8d7');
    joint(text,[x,8.55,z],[button,legend]);
  }
  for(const name of ['select','start','home','back']) {
    const p=LAYOUT[name];disk(root,2.24,.45,lip,p.x,7.64,p.z,name+'-collar');
    const button=disk(root,1.92,.58,shell,p.x,8.08,p.z,name+'-button',1.78);
    const [w,h]=name==='home'?[1.8,1.85]:name==='back'?[2,1.55]:name==='select'?[1.25,1.75]:[1.5,1.75];
    const legend=mark(root,name,p.x,8.41,p.z,w,h,'#93978c');
    joint(name,[p.x,8.08,p.z],[button,legend]);
  }
  // Raised shell bridge, not an inset tray. Its round stops meet the upper bezel
  // when closed; the switch itself remains slightly lower than those contact pads.
  mesh(root,aynBridgeGeometry(),shell,0,0,0,'raised-ayn-bridge');
  const aynMount=new THREE.Group();aynMount.name='inclined-ayn-switch-mount';
  aynMount.position.set(0,9.85,43.25);aynMount.rotation.x=AYN_SLOPE;root.add(aynMount);
  const aynFrame=roundedOutline(10,3.35,.55);
  aynFrame.holes.push(new THREE.Path(roundedOutline(9.5,3,.45).getPoints(20).reverse()));
  const aynSeam=mesh(aynMount,extrude(aynFrame,.08,.04),cavity,0,.07,0,'ayn-button-seam');aynSeam.rotation.x=-Math.PI/2;
  const aynButton=deckPlate(aynMount,9.3,2.85,.27,.45,cap,0,.17,0,'ayn-button',.04);
  const aynLegend=mark(aynMount,'AYN',0,.355,0,6.2,1.55,'#25292a');
  joint('AYN',[0,.17,0],[aynButton,aynLegend],aynMount);
  for(const [name,x] of [['left',-21.1],['right',21.1]]) {
    disk(root,1.9,.15,lip,x,9.96,42.6,name+'-lid-stop-seat');
    disk(root,1.65,.48,shell,x,10.22,42.6,name+'-lid-stop');
  }

  // Front fascia is perforated: actual through-holes with a dark interior behind it.
  const front=roundedOutline(136.8,12.2,.7,0,.6);
  capsuleHole(front,-40,0,23.8,5.9);capsuleHole(front,-19,0,11.6,5.9);
  slotHole(front,7.3,-.5,8.7,3.3,1.65);circularHole(front,23,-.5,2.55);
  slotHole(front,40,-.1,20,5.4,2.7);
  const frontPanel=mesh(root,extrude(front,.32,0),shell,0,0,46.92,'perforated-front-fascia');
  capsule(root,23.8,5.9,.35,lip,-40,0,46.2,'volume-outer-recess');
  capsule(root,21.65,4.7,.25,cavity,-40,0,46.35,'volume-recess');
  const volume=capsule(root,20.1,4,.5,shell,-40,0,46.65,'volume-rocker',.15);
  const volumeDown=mark(root,'−',-46,0,47.06,1.65,.27,'#858b81',false),volumeUp=mark(root,'+',-34,0,47.06,1.5,1.5,'#858b81',false);
  joint('volume',[-40,0,46.65],[volume,volumeDown,volumeUp]);
  capsule(root,11.6,5.9,.35,lip,-19,0,46.2,'power-outer-recess');
  capsule(root,10.2,4.7,.25,cavity,-19,0,46.35,'power-recess');
  const power=capsule(root,9.2,4,.5,shell,-19,0,46.65,'power-button',.15);
  const powerLegend=mark(root,'power',-19,0,47.06,2.05,2.3,'#8b9085',false);
  joint('power',[-19,0,46.65],[power,powerLegend]);
  plate(root,8.7,3.4,.6,1.65,black,7.3,-.5,45.8,'usb-c-socket');
  plate(root,5.3,.65,.4,.28,lip,7.3,-.5,46.15,'usb-c-tongue');
  const audio=disk(root,2.55,.7,black,23,-.5,46,'audio-socket');audio.rotation.x=Math.PI/2;
  const audioRim=ring(root,2.3,.18,cavity,23,-.5,46.85);audioRim.rotation.x=0;
  plate(root,19.5,4.9,.5,2.4,lip,40,-.1,46.7,'microsd-seam');
  plate(root,19,4.4,.45,2.1,shell,40,-.1,46.97,'microsd-cover');
  mark(root,'TF CARD',40,-.1,47.31,14.4,1.9,'#8a8f82',false);
  // Lanyard openings wrap around each front corner (front and side).
  for(const side of [-1,1]) {
    plate(root,1.6,3.5,.25,.65,cavity,side*70,2.8,45.85,'lanyard-front');
    const loop=plate(root,1.6,3.5,.25,.65,cavity,side*74.95,2.8,40.5,'lanyard-side');loop.rotation.y=side*Math.PI/2;
  }

  // Underside: offset circular fan inlet, horizontal ribs, three exhaust slots, four screws.
  // These details all lie on the broad flat part, outside the corner blends.
  const underDetails=new THREE.Group();underDetails.name='underside-details';underDetails.position.y=3.1;root.add(underDetails);
  disk(underDetails,13.1,.3,black,fan.x,-9.8,fan.z,'fan-dark-interior');
  for(const x of [-30,0,30])deckPlate(underDetails,27,3,.3,1.2,black,x,-9.9,-34.4,'exhaust-dark-interior');
  for(let row=0;row<15;row++) {
    const z=-27.5+row*2;
    for(const [x,w] of [[-25,31],[25,33]]) {
      if(x>0 && Math.abs(z-fan.z)<14)continue;
      deckPlate(underDetails,w,.26,.12,.12,lip,x,-10.56,z,'underside-rib',0);
    }
  }
  const underText=mark(underDetails,'AYN',-23,-10.64,-12,20,7,'#a5a69a');underText.rotation.x=Math.PI/2;underText.rotation.z=Math.PI;
  for(const [x,z] of [[-63,35],[63,35],[-44,-38],[44,-38]])screw(x,-10.68,z,false,underDetails);

  // Full-width hinge: narrow segmented joints, outer cheeks and central rotating barrel.
  const hinge=new THREE.Group();hinge.name='lid-pivot';hinge.position.set(0,10.6,-41);root.add(hinge);
  // A continuous moulded saddle closes the former uncovered rear strip of the
  // lower shell. It overlaps both the glass edge and the bottom of the barrel.
  deckPlate(root,148.6,12.6,2.3,6.2,shell,0,6.3,-40.5,'closed-hinge-saddle',.15);
  const barrel=disk(root,4.35,110.5,shell,0,10.6,-41,'hinge-barrel');barrel.rotation.z=Math.PI/2;
  for(const side of [-1,1]) {
    const cheek=mesh(root,hingeCheekGeometry(),shell,side*65.05,10.6,-41,side<0?'left-rounded-hinge-cap':'right-rounded-hinge-cap');
    cheek.rotation.z=-side*Math.PI/2;
    const joint=disk(root,4.36,.18,seam,side*55.325,10.6,-41,'hinge-joint');joint.rotation.z=Math.PI/2;
  }
  for(const side of [-1,1]) {
    const x=side*64, prefix=side<0?'L':'R';
    const socket=new THREE.Shape();socket.moveTo(-10.85,5.5);socket.lineTo(10.85,5.5);socket.lineTo(10.85,-.5);
    socket.bezierCurveTo(10.85,-6.2,5.2,-10.6,0,-10.6);socket.bezierCurveTo(-5.2,-10.6,-10.85,-6.2,-10.85,-.5);socket.closePath();
    // A frame surrounds the moving caps. A filled backing plate in this plane
    // would cover the trigger face as soon as it pivots into the housing.
    for(const [outline,offset] of [[bumperOutline(),2.6],[triggerOutline(),.2]]) {
      const points=outline.getPoints(24).map(p=>new THREE.Vector2(p.x*1.01,p.y*1.01+offset));
      socket.holes.push(new THREE.Path(points.reverse()));
    }
    // A moulded shell cup surrounds the moving stem and blends into the rear
    // cheek. Its open centre allows the paddle to rock into the housing.
    mesh(root,shoulderGeometry(socket,6.2,side,.12),shell,x,0,-43.65,prefix+'-shoulder-pocket');
    // Preserve the rear contact faces while extending the skirts into their
    // recessed housing; the former shallow caps stopped short of the sidewall.
    const bumper=mesh(root,shoulderGeometry(bumperOutline(),6,side),shell,x,2.6,-44.3,prefix+'1-bumper');
    const trigger=mesh(root,triggerGeometry(side),shell,x,.2,-44.1,prefix+'2-trigger');
    const l1=mark(root,prefix+'1',x,2.7,-47.57,4.15,2.6,'#979b8c',false);l1.rotation.y=Math.PI;
    const l2=mark(root,prefix+'2',x,-4,-47.57,4.15,2.6,'#979b8c',false);l2.rotation.y=Math.PI;
    joint(prefix+'1',[x,2.6,-44.3],[bumper,l1]);
    // In the owner's video the two ends move oppositely around an internal
    // fulcrum. An end-mounted pivot incorrectly levers the whole paddle out.
    joint(prefix+'2',[x,-4.2,-45.5],[trigger,l2]);
  }

  // Lid: a shallow, broad-radius shell. RoundedBoxGeometry would clamp these corners.
  plate(hinge,149,85.4,3.4,6,shell,0,43.4,-2.45,'lid-outer-shell',.15);
  plate(hinge,147.9,84.3,.42,5.8,lip,0,43.4,-.5,'lid-glass-gasket',.05);
  plate(hinge,147.1,83.5,.36,5.5,glassMaterial,0,43.4,-.08,'upper-full-glass',.04);
  plate(hinge,132.83,74.72,.02,.75,oledMaterial,0,43.4,.17,'upper-active-display',0);
  // Screens are intentionally dark so geometry/materials can be judged without invented UI.
  const setAngle=degrees=>{
    if(!Number.isFinite(degrees)||degrees<0||degrees>180)throw new RangeError('Hinge angle must be between 0 and 180 degrees');
    hinge.rotation.x=THREE.MathUtils.degToRad(90-degrees);root.updateMatrixWorld(true);
  };
  setAngle(135);
  const inputs=createInputRig(root,joints);
  return {root,hinge,setAngle,inputs};
}
