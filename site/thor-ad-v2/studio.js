// SPDX-License-Identifier: 0BSD
import * as T from 'three';
import {createThor,LAYOUT} from '../model-editor/thor.js';
import {roundedOutline} from '../model-editor/geometry.js';
import {ease} from './timeline.js';
import {cameraPose,smooth} from './motion.js';
import {CONTROLS,controlAnchor} from './controls.js';
import {createDesignMode} from '../model-editor/design-mode.js';

const WIDTH=720,HEIGHT=860,OFFSET=300;
const PINK='#d64d96',GREEN='#66ff00';

// Original housing and rainbow caps remain intact. Private Back/Home cap
// materials carry the trigger colours specified by the screenplay.
export async function createStudio(){
 const deviceLogo=new Image();deviceLogo.src='../assets/icon.png';await deviceLogo.decode();
 const renderer=new T.WebGLRenderer({antialias:true,alpha:true});
 renderer.setPixelRatio(1);renderer.setSize(WIDTH,HEIGHT);
 renderer.setClearColor(0xffffff,0);renderer.toneMapping=T.ACESFilmicToneMapping;
 renderer.toneMappingExposure=.92;
 renderer.shadowMap.enabled=true;renderer.shadowMap.type=T.PCFSoftShadowMap;
 const scene=new T.Scene();
 const camera=new T.OrthographicCamera(-110,110,130,-130,1,1600);
 const environment=new T.Scene();environment.background=new T.Color('#737373');
 for(const [position,scale,intensity] of [ [[-180,160,120],[170,260],3],[[160,160,-100],[90,240],2.5],[[0,260,0],[240,180],2] ]){
  const card=new T.Mesh(new T.PlaneGeometry(...scale),new T.MeshBasicMaterial({color:new T.Color().setScalar(intensity)}));
  card.position.set(...position);card.lookAt(0,0,0);environment.add(card);
 }
 const pmrem=new T.PMREMGenerator(renderer),env=pmrem.fromScene(environment,.03);
 scene.environment=env.texture;scene.environmentIntensity=.5;pmrem.dispose();
 environment.traverse(o=>{if(o.isMesh){o.geometry.dispose();o.material.dispose();}});
 scene.add(new T.HemisphereLight('#ffffff','#9fa4ae',1.6));
 for(const [x,y,z,p] of [[-150,260,180,2.5],[160,100,-130,1.3],[0,200,-250,1.5]]){
  const light=new T.DirectionalLight('#ffffff',p);light.position.set(x,y,z);scene.add(light);
  if(x<0){light.castShadow=true;light.shadow.mapSize.set(2048,2048);Object.assign(light.shadow.camera,{left:-170,right:170,top:190,bottom:-160,near:1,far:750});light.shadow.bias=-.0002;light.shadow.normalBias=.3;light.shadow.radius=4;}
 }
 const thor=createThor();scene.add(thor.root);thor.setAngle(150);
 const floor=new T.Mesh(new T.PlaneGeometry(1600,1600),new T.ShadowMaterial({opacity:.12}));
 floor.rotation.x=-Math.PI/2;floor.position.y=-16;floor.receiveShadow=true;floor.visible=false;scene.add(floor);

 // Reuse the model's contour renderer. Solid controls keep their geometry,
 // materials and physical motion while the housing becomes a design view.

 const targets=new Map();
 for(const control of CONTROLS)targets.set(control.name,controlAnchor(T,thor.root,control));
 targets.set('back',controlAnchor(T,thor.root,{joint:'back'}));
 const triggerCaps=['back','home'].map(name=>{
  const cap=thor.root.getObjectByName(name+'-button');cap.material=cap.material.clone();
  const light=new T.PointLight(PINK,0,17,2);light.position.copy(targets.get(name));light.position.y+=3;scene.add(light);
  return {name,cap,light,original:cap.material.color.clone()};
 });
 // These front-edge OEM controls are not shortcut inputs. Classify them as
 // housing when the film's design view records its solid-control list.
 const frontJoints=['volume','power'].map(name=>thor.root.getObjectByName(name+'-joint'));
 for(const joint of frontJoints)joint.userData.inputControl=null;
 const design=createDesignMode(thor.root);
 frontJoints.forEach((joint,i)=>joint.userData.inputControl=['volume','power'][i]);
 design.setViewport(WIDTH,HEIGHT);design.setSolidControls(true);
 // An x-ray of each actual shoulder cap resolves rear-facing geometry without
 // inventing a substitute button on the front of the handheld.
 const shoulderCaps=[];
 thor.root.traverse(o=>{
  if(!o.isMesh||!/[LR][12]-(bumper|trigger)/.test(o.name))return;
  shoulderCaps.push(o);
 });
 const shoulderHighlights=shoulderCaps.map(source=>{
  const overlay=new T.Mesh(source.geometry,new T.MeshBasicMaterial({color:PINK,transparent:true,opacity:0,depthTest:false,depthWrite:false,toneMapped:false}));
  overlay.position.copy(source.position);overlay.quaternion.copy(source.quaternion);overlay.scale.copy(source.scale);overlay.renderOrder=205;source.parent.add(overlay);
  return {name:source.name.slice(0,2),overlay};
 });
 function hoop(radius,thickness,color){
  const m=new T.MeshBasicMaterial({color,transparent:true,opacity:0,depthTest:false,depthWrite:false,toneMapped:false});
  const mesh=new T.Mesh(new T.TorusGeometry(radius,thickness,10,72),m);mesh.rotation.x=-Math.PI/2;mesh.renderOrder=210;scene.add(mesh);return mesh;
 }
 const triggers=['back','home'].map(name=>({name,ring:hoop(2.7,.16,PINK)}));
 const layers=[PINK,GREEN].map(color=>{
  const canvas=document.createElement('canvas');canvas.width=1068;canvas.height=630;
  const texture=new T.CanvasTexture(canvas);texture.colorSpace=T.SRGBColorSpace;texture.generateMipmaps=false;texture.minFilter=T.LinearFilter;
  const geometry=new T.ShapeGeometry(roundedOutline(178,105,13));
  const uv=geometry.attributes.uv,p=geometry.attributes.position;for(let i=0;i<uv.count;i++)uv.setXY(i,p.getX(i)/178+.5,p.getY(i)/105+.5);
  const plane=new T.Mesh(geometry,new T.MeshBasicMaterial({color,transparent:true,opacity:0,side:T.DoubleSide,depthTest:false,depthWrite:false,toneMapped:false}));plane.rotation.x=-Math.PI/2;plane.renderOrder=206;scene.add(plane);
  const points=roundedOutline(178,105,13).getPoints(80).map(p=>new T.Vector3(p.x,0,-p.y));
  const edge=new T.LineLoop(new T.BufferGeometry().setFromPoints(points),new T.LineBasicMaterial({color,transparent:true,opacity:0,depthTest:false,depthWrite:false}));edge.renderOrder=207;scene.add(edge);
  const layout=new T.Mesh(geometry,new T.MeshBasicMaterial({map:texture,transparent:true,opacity:0,depthTest:false,depthWrite:false,toneMapped:false}));layout.rotation.x=-Math.PI/2;layout.renderOrder=208;scene.add(layout);return {plane,edge,layout,texture,canvas,color};
 });
 const wave=hoop(1,.35,PINK);

 function screen(name,w,h){
  const c=document.createElement('canvas');c.width=640;c.height=480;
  const tex=new T.CanvasTexture(c);tex.colorSpace=T.SRGBColorSpace;tex.generateMipmaps=false;tex.minFilter=T.LinearFilter;
  const geometry=new T.ShapeGeometry(roundedOutline(w,h,.6)),p=geometry.attributes.position,uv=geometry.attributes.uv;
  for(let i=0;i<uv.count;i++)uv.setXY(i,p.getX(i)/w+.5,p.getY(i)/h+.5);
  const mesh=new T.Mesh(geometry,new T.MeshBasicMaterial({map:tex,toneMapped:false,polygonOffset:true,polygonOffsetFactor:-3,polygonOffsetUnits:-3}));
  mesh.position.z=.16;thor.root.getObjectByName(name).add(mesh);
  return {ctx:c.getContext('2d'),tex,mesh};
 }
 const top=screen('upper-active-display',132.7,74.6),bottom=screen('lower-active-display',75,65.3);
 // Match the editor's restrained wire watermark. Attach it to the physical
 // display after the design renderer has collected the housing meshes.
 const logoCanvas=document.createElement('canvas');logoCanvas.width=logoCanvas.height=512;
 logoCanvas.getContext('2d').drawImage(deviceLogo,0,0,512,512);
 const logoMap=new T.CanvasTexture(logoCanvas);logoMap.colorSpace=T.SRGBColorSpace;
 const wireLogo=new T.Mesh(new T.PlaneGeometry(41,41),new T.ShaderMaterial({
  uniforms:{map:{value:logoMap},tint:{value:new T.Color('#82b6c4')},opacity:{value:0}},
  vertexShader:'varying vec2 screenUv;void main(){screenUv=uv;gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0);}',
  fragmentShader:`uniform sampler2D map;uniform vec3 tint;uniform float opacity;varying vec2 screenUv;
   void main(){
    vec4 pixel=texture2D(map,screenUv);
    float mask=1.0-smoothstep(0.462,0.467,length(screenUv-vec2(0.5)));
    float lightness=dot(pixel.rgb,vec3(0.2126,0.7152,0.0722));
    gl_FragColor=vec4(tint*(0.08+0.92*lightness),pixel.a*mask*opacity);
    #include <colorspace_fragment>
   }`,
  transparent:true,depthWrite:false,toneMapped:false,
  polygonOffset:true,polygonOffsetFactor:-3,polygonOffsetUnits:-3
 }));
 wireLogo.position.z=.17;wireLogo.visible=false;
 thor.root.getObjectByName('upper-active-display').add(wireLogo);

 // Two quiet silhouettes remain recognisable when exchanged, without text,
 // artwork or saturated UI competing with the actual physical controls.
 const art=[false,true].map(kind=>{
  const c=document.createElement('canvas');c.width=640;c.height=480;const ctx=c.getContext('2d');
  ctx.fillStyle=kind?'#555d60':'#3b464f';ctx.fillRect(0,0,640,480);
  ctx.filter='blur(15px)';ctx.fillStyle=kind?'#687072':'#4a5660';
  if(kind){for(let i=0;i<3;i++){ctx.beginPath();ctx.roundRect(70,75+i*116,500,76,18);ctx.fill();}}
  else{ctx.beginPath();ctx.moveTo(-40,400);ctx.lineTo(180,160);ctx.lineTo(340,330);ctx.lineTo(500,110);ctx.lineTo(700,360);ctx.lineTo(700,500);ctx.lineTo(-40,500);ctx.fill();ctx.fillStyle='#657079';ctx.beginPath();ctx.arc(490,100,30,0,Math.PI*2);ctx.fill();}
  return c;
 });
 function drawScreen(screen,isTop,s){
  const c=screen.ctx,first=isTop?0:1,second=1-first;
  c.drawImage(art[first],0,0);
  if(s.t>=19){c.fillStyle=isTop?'#b8c9d02e':'#d4dce02a';c.fillRect(0,0,640,480);}
  if(isTop){
   // Fade only the emitted logo into the existing content, with no separate
   // backdrop or halo. Compensate the texture aspect for the physical display.
   const height=264,width=height*(640/480)/(132.7/74.6);
   c.save();c.globalAlpha=.82*smooth((s.t-.85)/.9);
   c.beginPath();c.ellipse(320,240,width*.467,height*.467,0,0,Math.PI*2);c.clip();
   c.drawImage(deviceLogo,320-width/2,240-height/2,width,height);c.restore();
  }

  screen.tex.needsUpdate=true;
 }
 // The drawing uses model coordinates, so every symbol stays registered to
 // the real deck. All marks share the pane transform and rise as one object.
 function drawLayout(canvas,color,s,layer){
  const c=canvas.getContext('2d');c.clearRect(0,0,canvas.width,canvas.height);c.save();c.scale(6,6);c.translate(89,52.5);
  c.strokeStyle=color;c.fillStyle=color;c.lineWidth=.75;c.lineJoin='round';
  const glow=layer?s.paneGlow[layer]:Math.max(s.paneGlow[0],s.panes[1]*.75);
  c.shadowColor=color;c.shadowBlur=glow*9;
  function cap(name,x,z,w,h,label,r=2){
   c.globalAlpha=.80+.20*glow;c.beginPath();c.roundRect(x-w/2,z-h/2,w,h,r);
   if(glow>0){c.globalAlpha=glow*.48;c.fill();c.globalAlpha=1;}
   c.stroke();
   if(label){c.font='600 3.1px Arial';c.textAlign='center';c.textBaseline='middle';c.fillText(label,x,z+.15);}
   c.globalAlpha=1;
  }
  for(const name of ['A','B','X','Y']){const p=targets.get(name);cap(name,p.x,p.z,7.4,7.4,name,3);}
  const dp=LAYOUT.dpad;c.globalAlpha=.9;c.beginPath();
  for(const [i,[x,z]]of [[-3,-9],[3,-9],[3,-3],[9,-3],[9,3],[3,3],[3,9],[-3,9],[-3,3],[-9,3],[-9,-3],[-3,-3]].entries()){if(i)c.lineTo(dp.x+x,dp.z+z);else c.moveTo(dp.x+x,dp.z+z);}c.closePath();if(glow>0){c.globalAlpha=glow*.48;c.fill();c.globalAlpha=1;}c.stroke();
  for(const [name,symbol,x,z]of [['dpadUp','↑',0,-6],['dpadDown','↓',0,6],['dpadLeft','←',-6,0],['dpadRight','→',6,0]]){c.globalAlpha=.95;c.font='3.8px Arial';c.textAlign='center';c.textBaseline='middle';c.fillText(symbol,dp.x+x,dp.z+z);}
  for(const side of ['left','right']){const p=LAYOUT[side+'Stick'];c.globalAlpha=.85;c.beginPath();c.arc(p.x,p.z,9,0,Math.PI*2);if(glow>0){c.globalAlpha=glow*.48;c.fill();c.globalAlpha=1;}c.stroke();c.beginPath();c.arc(p.x,p.z,6,0,Math.PI*2);c.stroke();for(const [symbol,x,z]of [['↑',0,-11],['↓',0,11],['←',-11,0],['→',11,0]]){c.font='3.5px Arial';c.fillText(symbol,p.x+x,p.z+z);}c.font='2.5px Arial';c.fillText(side==='left'?'L3':'R3',p.x,p.z);}
  for(const name of ['select','start','home','back']){if(name==='back'||(name==='home'&&s.home>.9))continue;const p=LAYOUT[name];cap(name,p.x,p.z,8,5,name==='select'?'SEL':name.toUpperCase(),2);}
  for(const side of ['L','R']){const x=side==='L'?-64:64;cap(side+'2',x,-47,16,7,side+'2');cap(side+'1',x,-37,16,7,side+'1');}
  // A subtle display opening anchors the surrounding controls without adding UI.
  c.globalAlpha=.12;c.lineWidth=.3;c.beginPath();c.roundRect(-37.5,-28,75,65,2);c.stroke();c.restore();
 }
 const center=new T.Vector3(),v=new T.Vector3();
 const regular=document.createElement('canvas'),technical=document.createElement('canvas'),output=document.createElement('canvas');
 for(const c of [regular,technical,output]){c.width=WIDTH;c.height=HEIGHT;}
 const normalContext=regular.getContext('2d'),wireContext=technical.getContext('2d'),composite=output.getContext('2d');
 return {renderer,render(s){
  const turn=smooth((s.t-26)/1.83);
  thor.root.rotation.set(0,turn*Math.PI*2,0);
  thor.root.position.y=-170*(1-smooth(s.t/.85));
  thor.setAngle(150*smooth((s.t-.5)/1.0)*(1-smooth((s.t-26.04)/.89)+smooth((s.t-27.01)/.95)));
  thor.inputs.releaseAll();
  thor.inputs.setButton('back',s.buttons.back||0);
  thor.inputs.setButton('home',s.buttons.home||0);
  const pose=cameraPose(s.t),half=pose.half;
  center.fromArray(pose.target);camera.position.fromArray(pose.position);
  camera.lookAt(center);camera.updateMatrixWorld();
  // Fixed composition per shot prevents framing from pumping with every press.
  camera.left=-half*WIDTH/HEIGHT;camera.right=-camera.left;camera.top=half;camera.bottom=-half;camera.updateProjectionMatrix();
  // Render complementary passes during the reveal, then retain a complete
  // wireframe until the macro cut. Display artwork must not cover its contours.
  const wire=s.wire;
  const triggerReveal=ease((s.t-4.6)/.18)*(1-ease((s.t-19)/.45));
  for(const {name,cap,light,original}of triggerCaps){
   const enabled=triggerReveal*(name==='home'?s.home:1),pressure=s.buttons[name]||0;
   const colour=new T.Color(name==='home'&&s.greenHome>0?GREEN:'#c32c79');
   cap.material.color.copy(original).lerp(new T.Color(name==='home'&&s.greenHome>0?'#398700':'#8b154c'),enabled);
   cap.material.emissive.copy(colour);cap.material.emissiveIntensity=enabled*(.08+pressure*.16);
   light.color.copy(colour);light.intensity=enabled*(5+pressure*7);
  }
  for(const {name,ring}of triggers){
   const pressure=s.buttons[name]||0,enabled=triggerReveal*(name==='home'?s.home:1);
   ring.position.copy(targets.get(name));ring.position.y+=.5;
   const elapsed=s.t-(name==='back'?4.6:14.5);
   const pulse=elapsed>=0&&elapsed<1.04?Math.sin(Math.PI*elapsed/.52)**2:0;
   ring.material.color.set(name==='home'&&s.greenHome>0?GREEN:PINK);
   ring.material.opacity=enabled*(.45+pulse*.55);
   ring.scale.setScalar(1+pulse*.65);
  }
  for(const {name,overlay}of shoulderHighlights){overlay.material.color.set(s.t>=17.3&&s.t<19?GREEN:PINK);overlay.material.opacity=ease((s.t-4.05)/.35)*(1-ease((s.t-19.6)/.35))*(.55*(s.buttons[name]||0));}
  layers.forEach((pane,layer)=>{
   const {plane,edge,layout,texture,canvas,color}=pane;
   const amount=s.panes[layer];
   const active=1;
   plane.position.set(0,layer?24+30*smooth((s.t-15.05)/1.15):9+15*amount,0);edge.position.copy(plane.position);
   plane.material.opacity=amount*active*.075;edge.material.opacity=amount*active*.7;
   layout.position.copy(plane.position);layout.position.y+=.1;layout.material.opacity=amount*active;
   drawLayout(canvas,color,s,layer);texture.needsUpdate=true;
  });
  wave.material.opacity=0;
  drawScreen(top,true,s);drawScreen(bottom,false,s);
  composite.clearRect(0,0,WIDTH,HEIGHT);
  if(wire<1){
   design.setEnabled(false);top.mesh.visible=true;bottom.mesh.visible=true;wireLogo.visible=false;
   renderer.render(scene,camera);normalContext.clearRect(0,0,WIDTH,HEIGHT);normalContext.drawImage(renderer.domElement,0,0);
   composite.drawImage(regular,0,0);
   if(s.t>=1.45&&s.t<=2.2){
    const progress=smooth((s.t-1.45)/.75);
    const x=-450+progress*1650;
    const gloss=composite.createLinearGradient(x,0,x+280,120);
    gloss.addColorStop(0,'#ffffff00');gloss.addColorStop(.42,'#ffffff00');
    gloss.addColorStop(.5,'#ffffff70');gloss.addColorStop(.58,'#ffffff00');gloss.addColorStop(1,'#ffffff00');
    composite.save();composite.globalCompositeOperation='source-atop';
    composite.fillStyle=gloss;composite.fillRect(0,0,WIDTH,HEIGHT);composite.restore();
   }
  }
  if(wire>0){
   design.setEnabled(true);top.mesh.visible=false;bottom.mesh.visible=false;
   wireLogo.visible=true;wireLogo.material.uniforms.opacity.value=.32*smooth((s.t-5.1)/.95);
   renderer.render(scene,camera);wireContext.clearRect(0,0,WIDTH,HEIGHT);wireContext.drawImage(renderer.domElement,0,0);
   const project=p=>{p.project(camera);return {x:(p.x+1)*WIDTH/2,y:(1-p.y)*HEIGHT/2};};
   const origin=targets.get('back').clone();origin.y+=.5;
   const o=project(origin.clone()),x=project(origin.clone().add(new T.Vector3(1,0,0))),z=project(origin.clone().add(new T.Vector3(0,0,1)));
   // Project the very same deck-plane circle as the original light wave.
   // The affine ellipse follows the camera; it is never a screen-space wipe.
   const radius=2+ease((s.t-5.1)/.95)*210;
   composite.save();composite.globalAlpha=1-ease((s.t-18.95)/.5);
   if(s.t<6.05){
    composite.transform(x.x-o.x,x.y-o.y,z.x-o.x,z.y-o.y,o.x,o.y);
    composite.beginPath();composite.arc(0,0,radius,0,Math.PI*2);composite.clip();
    const matrix=composite.getTransform();composite.resetTransform();composite.drawImage(technical,0,0);
    composite.setTransform(matrix);composite.strokeStyle='#ed81bb';composite.lineWidth=.8;composite.shadowColor='#ef68ad';composite.shadowBlur=10;
    composite.beginPath();composite.arc(0,0,radius-.5,0,Math.PI*2);composite.stroke();
   }else composite.drawImage(technical,0,0);
   composite.restore();
  }
  return output;
 },point(name,layer){v.copy(targets.get(name));if(layer!==undefined)v.y=layers[layer].plane.position.y;v.project(camera);return{x:(v.x+1)*WIDTH/2,y:(1-v.y)*HEIGHT/2+OFFSET};}};
}
