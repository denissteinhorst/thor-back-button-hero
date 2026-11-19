// SPDX-License-Identifier: 0BSD
import * as THREE from 'three';
import { sweepInverse } from './intro-timeline.js?v=ad-1';
import { createReachGauge } from './intro-reach-gauge.js?v=ad-1';

const green='#67d58a',yellow='#f2ce58',red='#f05c57';
const targets=[
  ['home','home-button',green],['leftStick','left-stick-rubber-cap',green],
  ['L1','L1-bumper',green],['L2','L2-trigger',green],['R1','R1-bumper',green],['R2','R2-trigger',green],
  ['start','start-button','#b9d768'],['select','select-button',yellow],
  ['A','button-A','#ffd05a'],['B','button-B','#f7c952'],['X','button-X','#efd45e'],['Y','button-Y','#e9d263'],
  ['rightStick','right-stick-rubber-cap',red]
];
const directions=[['dpadUp',0,-6.25],['dpadRight',6.25,0],['dpadDown',0,6.25],['dpadLeft',-6.25,0]];
const tau=Math.PI*2;

export function createRadialDemo(thor,scene,renderer) {
  const gauge=createReachGauge(scene,renderer);
  const group=new THREE.Group();group.name='intro-radial-demo';group.visible=false;scene.add(group);
  const controls=new Map(),points=[];
  for(const [name,mesh,color] of targets){const target={name,object:thor.root.getObjectByName(mesh),color:new THREE.Color(color),offset:null};points.push(target);controls.set(name,target);}
  for(const [name,x,z] of directions)points.push({name,object:thor.root.getObjectByName('dpad-joint'),offset:new THREE.Vector3(x,2,z),color:new THREE.Color(green)});
  for(const target of points){target.baseColour=target.color.clone();target.enabled=true;}
  const parts=[];
  thor.root.traverse(object=>{
    if(!object.isMesh||object.name.startsWith('mark-'))return;
    let name=null;for(let parent=object;parent;parent=parent.parent)if(parent.userData.inputControl){name=parent.userData.inputControl;break;}
    if(!controls.has(name)&&name!=='dpad')return;
    if(Array.isArray(object.material))return;
    parts.push({object,name,original:object.material,tint:object.material.clone()});
  });
  // Separate, depth-independent copies expose the physical shoulder shapes
  // during their colour cue; the native parts retain ordinary depth testing.
  const shoulders=[];
  for(const part of parts.filter(part=>/^[LR][12]$/.test(part.name))){
    const material=new THREE.MeshBasicMaterial({color:controls.get(part.name).color,transparent:true,opacity:0,depthTest:false,depthWrite:false,toneMapped:false,side:THREE.DoubleSide});
    const mesh=new THREE.Mesh(part.object.geometry,material);mesh.position.copy(part.object.position);mesh.quaternion.copy(part.object.quaternion);mesh.scale.copy(part.object.scale);
    mesh.name='intro-shoulder-xray';mesh.renderOrder=135;mesh.visible=false;part.object.parent.add(mesh);
    const canvas=document.createElement('canvas');canvas.width=256;canvas.height=128;
    const context=canvas.getContext('2d');context.fillStyle='#f4fff7';context.textAlign='center';context.textBaseline='middle';context.font='600 92px Arial, sans-serif';context.fillText(part.name,128,68);
    const map=new THREE.CanvasTexture(canvas);map.colorSpace=THREE.SRGBColorSpace;renderer?.initTexture(map);
    const label=new THREE.Sprite(new THREE.SpriteMaterial({map,transparent:true,depthTest:false,depthWrite:false,toneMapped:false}));
    label.name='intro-shoulder-mark';label.scale.set(5.4,2.7,1);label.position.copy(part.object.position);label.renderOrder=136;label.visible=false;part.object.parent.add(label);
    const originalLegend=thor.root.getObjectByName('mark-'+part.name);
    if(originalLegend)label.position.copy(part.object.parent.worldToLocal(originalLegend.getWorldPosition(new THREE.Vector3())));
    shoulders.push({name:part.name,mesh,label});
  }
  for(const target of points){
    target.ring=new THREE.Mesh(new THREE.RingGeometry(target.name.endsWith('Stick')?6.4:3.2,target.name.endsWith('Stick')?7.3:4.1,48),new THREE.MeshBasicMaterial({color:'#d5ffff',transparent:true,opacity:0,depthTest:false,depthWrite:false,toneMapped:false}));
    target.ring.name='intro-control-highlight';target.ring.renderOrder=120;group.add(target.ring);
  }
  const fanGeometry=new THREE.BufferGeometry(),fanVertices=new Float32Array(130*3),fanAlpha=new Float32Array(130),fanIndices=[];
  for(let i=1;i<129;i++)fanIndices.push(0,i,i+1);
  fanGeometry.setAttribute('position',new THREE.BufferAttribute(fanVertices,3));fanGeometry.setAttribute('trailAlpha',new THREE.BufferAttribute(fanAlpha,1));fanGeometry.setIndex(fanIndices);
  const fan=new THREE.Mesh(fanGeometry,new THREE.ShaderMaterial({
    uniforms:{trailOpacity:{value:0},colour:{value:new THREE.Color('#64c8b0')}},
    vertexShader:'attribute float trailAlpha;varying float trail;void main(){trail=trailAlpha;gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0);}',
    fragmentShader:'uniform float trailOpacity;uniform vec3 colour;varying float trail;void main(){float alpha=trail*trailOpacity;if(alpha<0.001)discard;gl_FragColor=vec4(colour,alpha);\n#include <colorspace_fragment>\n}',
    transparent:true,depthTest:false,depthWrite:false,toneMapped:false,side:THREE.DoubleSide
  }));
  fan.frustumCulled=false;fan.renderOrder=100;group.add(fan);
  const beam=new THREE.Mesh(new THREE.PlaneGeometry(1,1),new THREE.MeshBasicMaterial({color:'#d5ffff',transparent:true,opacity:.85,depthTest:false,depthWrite:false,toneMapped:false}));
  const beamGlow=new THREE.Mesh(new THREE.PlaneGeometry(1,1),new THREE.MeshBasicMaterial({color:'#72decb',transparent:true,opacity:.16,depthTest:false,depthWrite:false,toneMapped:false}));
  beam.renderOrder=102;beamGlow.renderOrder=101;group.add(beamGlow,beam);

  // D-pad quadrants share one physical rocker. Reveal portions of its original
  // material without splitting its mesh or inventing four independent keycaps.
  const rocker=thor.root.getObjectByName('dpad-rocker'),flags={value:new THREE.Vector4()},levels={value:new THREE.Vector4()},dpadColour={value:new THREE.Color(green)};
  const dpadMaterial=rocker.material.clone();dpadMaterial.polygonOffset=true;dpadMaterial.polygonOffsetFactor=-2;dpadMaterial.polygonOffsetUnits=-3;
  dpadMaterial.onBeforeCompile=shader=>{
    shader.uniforms.radialFlags=flags;shader.uniforms.radialLevels=levels;shader.uniforms.radialColour=dpadColour;
    shader.vertexShader='varying vec2 radialPosition;\n'+shader.vertexShader.replace('#include <begin_vertex>','#include <begin_vertex>\nradialPosition=position.xy;');
    shader.fragmentShader='uniform vec4 radialFlags;uniform vec4 radialLevels;uniform vec3 radialColour;varying vec2 radialPosition;\n'+shader.fragmentShader;
    shader.fragmentShader=shader.fragmentShader.replace('#include <color_fragment>',`#include <color_fragment>
      vec4 quadrant=abs(radialPosition.y)>=abs(radialPosition.x)?(radialPosition.y>=0.0?vec4(1,0,0,0):vec4(0,0,1,0)):(radialPosition.x>=0.0?vec4(0,1,0,0):vec4(0,0,0,1));
      if(dot(quadrant,radialFlags)<0.5)discard;
      float radialGlow=dot(quadrant,radialLevels);
      diffuseColor.rgb=mix(diffuseColor.rgb,radialColour,radialGlow*.9);`);
    shader.fragmentShader=shader.fragmentShader.replace('#include <emissivemap_fragment>','#include <emissivemap_fragment>\ntotalEmissiveRadiance+=radialColour*radialGlow*.8;');
  };
  dpadMaterial.customProgramCacheKey=()=> 'intro-dpad-quadrants-v2';
  const dpadOverlay=new THREE.Mesh(rocker.geometry,dpadMaterial);dpadOverlay.name='intro-dpad-quarters';dpadOverlay.visible=false;rocker.add(dpadOverlay);
  const legends=[];
  thor.root.getObjectByName('dpad-joint').traverse(object=>{if(object.isMesh&&object.name.startsWith('mark-'))legends.push({original:object,clone:object.clone(false)});});
  for(const entry of legends){entry.clone.name='intro-dpad-legend';entry.clone.visible=false;entry.original.parent.add(entry.clone);}
  let configured=null,start=0,end=0,radius=0,current=null,restored=[],role=null;
  const projected=new THREE.Vector3(),backWorld=new THREE.Vector3(),backView=new THREE.Vector3();
  function setRole(value) {
    if(role===value)return;role=value;
    for(const target of points){
      target.enabled=!(role==='home'&&target.name==='home');target.color.copy(target.baseColour);
      if(role==='home'){
        if(target.name==='rightStick'||['A','B','X','Y'].includes(target.name))target.color.set(green);
        else if(target.name==='start'||target.name==='select')target.color.set(yellow);
        else if(target.name==='leftStick'||target.name.startsWith('dpad'))target.color.set(red);
      }
    }
    dpadColour.value.set(role==='home'?red:green);
  }
  function allRevealed(){return [...controls.keys()].filter(name=>!(role==='home'&&name==='home')).concat('dpad').sort();}
  function pointWorld(target,out){target.object.getWorldPosition(out);if(target.offset)out.add(target.offset);else out.y+=.7;return out;}
  function configure(camera) {
    const key=[role,...camera.matrixWorld.elements,...camera.projectionMatrix.elements].join(',');
    if(configured===key)return;
    configured=key;thor.root.updateMatrixWorld(true);camera.updateMatrixWorld(true);
    thor.root.getObjectByName(role+'-joint').getWorldPosition(backWorld);backWorld.y+=.35;
    backView.copy(backWorld).applyMatrix4(camera.matrixWorldInverse);
    group.position.copy(backWorld);group.quaternion.copy(camera.quaternion);
    for(const target of points){
      pointWorld(target,projected).applyMatrix4(camera.matrixWorldInverse);
      const ratio=backView.z/projected.z;
      target.x=projected.x*ratio-backView.x;target.y=projected.y*ratio-backView.y;
      target.angle=Math.atan2(target.y,target.x);target.ring.position.set(target.x,target.y,.03);
    }
    const active=points.filter(target=>target.enabled),counterclockwise=role==='home';
    start=points.find(target=>target.name===(counterclockwise?'rightStick':'home')).angle+(counterclockwise?-.035:.035);end=start;radius=0;
    for(const target of active){
      if(counterclockwise){while(target.angle<start)target.angle+=tau;end=Math.max(end,target.angle);}
      else {while(target.angle>start)target.angle-=tau;end=Math.min(end,target.angle);}
      radius=Math.max(radius,Math.hypot(target.x,target.y));
    }
    end+=counterclockwise ? .035 : -.035;radius+=8;
    for(const target of active)target.hitAt=(start-target.angle)/(start-end);
    radius=gauge.configure(thor.root,camera,backWorld,start,end,radius);
    for(const entry of legends){
      const position=entry.original.getWorldPosition(new THREE.Vector3()),centre=thor.root.getObjectByName('dpad-joint').getWorldPosition(new THREE.Vector3());
      const dx=position.x-centre.x,dz=position.z-centre.z;
      entry.direction=Math.abs(dz)>=Math.abs(dx)?(dz<0?'dpadUp':'dpadDown'):(dx>0?'dpadRight':'dpadLeft');
    }
  }
  function reset() {
    gauge.hide();
    group.visible=false;dpadOverlay.visible=false;legends.forEach(entry=>entry.clone.visible=false);
    shoulders.forEach(shoulder=>{shoulder.mesh.visible=false;shoulder.label.visible=false;});
    for(const part of parts)if(part.object.material===part.tint)part.object.material=part.original;
    current=null;restored=[];
  }
  function update(frame,camera) {
    setRole(frame.radialOrigin??'back');
    if(!frame.radialStarted){reset();return {revealedControls:frame.retainButtons?allRevealed():[]};}
    configure(camera);current=frame;
    gauge.update(frame);
    const hits=new Set(),reveal=new Set(),hitDuration=.14*frame.radialDuration/1.2;
    for(const target of points){
      if(!target.enabled){target.hit=false;target.glow=target.colourStrength=0;target.ring.visible=false;continue;}
      target.hit=frame.radialMoving&&frame.radialProgress>=target.hitAt;
      const hitTime=sweepInverse(target.hitAt)*frame.radialDuration;
      target.age=frame.radialElapsed-hitTime;
      target.glow=target.hit&&target.age>=0&&target.age<hitDuration?Math.exp(-target.age/(hitDuration*.4)):0;
      target.colourStrength=target.hit?frame.radialColour:0;
      if(target.hit){hits.add(target.name);if(controls.has(target.name))reveal.add(target.name);}
      target.ring.material.color.copy(target.color);
      target.ring.material.opacity=frame.radialProgress<1?target.glow:0;
      target.ring.visible=target.ring.material.opacity>.001;
      target.ring.scale.setScalar(1+target.glow*.25);
    }
    const allDpad=directions.every(([name])=>hits.has(name));if(allDpad)reveal.add('dpad');
    directions.forEach(([name],i)=>{flags.value.setComponent(i,hits.has(name)?1:0);levels.value.setComponent(i,points.find(target=>target.name===name).colourStrength);});
    dpadOverlay.visible=!allDpad&&directions.some(([name])=>hits.has(name));
    legends.forEach(entry=>entry.clone.visible=!allDpad&&hits.has(entry.direction));
    restored=[...reveal].sort();
    const angle=start+(end-start)*frame.radialProgress;
    for(const shoulder of shoulders){
      const amount=controls.get(shoulder.name).colourStrength;
      shoulder.mesh.visible=shoulder.label.visible=amount>.001;
      shoulder.mesh.material.opacity=amount*.8;shoulder.label.material.opacity=amount;
    }
    fanVertices.fill(0);fanAlpha.fill(0);
    for(let i=0;i<=128;i++){
      const a=start+(angle-start)*i/128;fanVertices[(i+1)*3]=Math.cos(a)*radius;fanVertices[(i+1)*3+1]=Math.sin(a)*radius;
      const angularProgress=(start-a)/(start-end),passedAt=sweepInverse(angularProgress)*frame.radialDuration;
      const age=Math.max(0,frame.radialElapsed-passedAt),trailDuration=.18*frame.radialDuration/1.2;
      fanAlpha[i+1]=Math.max(0,1-age/trailDuration)**2;
    }
    fanGeometry.attributes.position.needsUpdate=true;
    fanGeometry.attributes.trailAlpha.needsUpdate=true;
    fan.visible=beam.visible=beamGlow.visible=frame.radialFade>0;
    fan.material.uniforms.trailOpacity.value=.16*frame.radialFade;beam.material.opacity=.85*frame.radialFade;beamGlow.material.opacity=.16*frame.radialFade;
    for(const [mesh,width] of [[beam,.55],[beamGlow,2.4]]){mesh.position.set(Math.cos(angle)*radius/2,Math.sin(angle)*radius/2,.02);mesh.rotation.z=angle;mesh.scale.set(radius,width,1);}
    group.visible=frame.radialFade>0||points.some(target=>target.ring.visible);
    return {revealedControls:restored};
  }
  function decorate() {
    if(!current)return;
    for(const part of parts){
      if(!restored.includes(part.name))continue;
      const target=part.name==='dpad'?{color:dpadColour.value,colourStrength:Math.max(...points.filter(p=>p.name.startsWith('dpad')).map(p=>p.colourStrength))}:controls.get(part.name);
      const amount=target.colourStrength;
      if(amount<=0){part.object.material=part.original;continue;}
      const color=target.color;
      if(part.tint.color)part.tint.color.copy(part.original.color).lerp(color,amount);
      if(part.tint.emissive){part.tint.emissive.copy(color).multiplyScalar(amount*.8);part.tint.emissiveIntensity=1;}
      part.object.material=part.tint;
    }
  }
  return {update,decorate,reset};
}
