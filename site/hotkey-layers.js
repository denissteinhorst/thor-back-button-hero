// SPDX-License-Identifier: 0BSD
import * as T from 'three';
import {createThor,LAYOUT} from './model-editor/thor.js';
import {roundedOutline} from './model-editor/geometry.js';
import {createDesignMode} from './model-editor/design-mode.js';
import {CONTROLS,controlAnchor} from './thor-ad-v2/controls.js';
import {cameraPose} from './thor-ad-v2/motion.js';
import {frameForMode,PINK,GREEN,BACK_HEIGHT,HOME_HEIGHT} from './hotkey-layer-state.js';

const PARTS=['back','home','homeEnabled','independent'];
const ease=value=>value*value*(3-2*value);

// Both panes show the same physical inputs, as in the film, not example actions.
function paintDeck(canvas,targets,color,homeIsModifier){
  const c=canvas.getContext('2d');c.clearRect(0,0,canvas.width,canvas.height);
  c.save();c.scale(6,6);c.translate(89,52.5);c.strokeStyle=color;c.fillStyle=color;c.lineWidth=.8;c.textAlign='center';c.textBaseline='middle';
  function cap(x,z,w,h,label,r=2){
    c.beginPath();c.roundRect(x-w/2,z-h/2,w,h,r);c.stroke();
    if(label){c.font='650 3.4px system-ui, sans-serif';c.fillText(label,x,z);}
  }
  for(const control of CONTROLS){
    if(control.stick||control.name.startsWith('dpad'))continue;
    if(control.name==='home'&&homeIsModifier)continue;
    const point=targets.get(control.name);
    if(control.shoulder)cap(control.name[0]==='L'?-64:64,control.name[1]==='1'?-37:-47,16,7,control.label);
    else cap(point.x,point.z,control.name.length===1?7.4:9,control.name.length===1?7.4:5,control.label,control.name.length===1?3:2);
  }
  const dpad=LAYOUT.dpad;
  c.beginPath();for(const [i,[x,z]]of[[-3,-9],[3,-9],[3,-3],[9,-3],[9,3],[3,3],[3,9],[-3,9],[-3,3],[-9,3],[-9,-3],[-3,-3]].entries()){if(i)c.lineTo(dpad.x+x,dpad.z+z);else c.moveTo(dpad.x+x,dpad.z+z);}c.closePath();c.stroke();
  c.font='650 3.7px system-ui, sans-serif';
  for(const control of CONTROLS.filter(item=>item.name.startsWith('dpad'))){const p=targets.get(control.name);c.fillText(control.label,p.x,p.z);}
  for(const side of ['left','right']){
    const p=LAYOUT[side+'Stick'];c.beginPath();c.arc(p.x,p.z,9,0,Math.PI*2);c.stroke();
    c.beginPath();c.arc(p.x,p.z,6,0,Math.PI*2);c.stroke();
    c.font='650 3px system-ui, sans-serif';c.fillText(side==='left'?'L3':'R3',p.x,p.z);
    for(const [text,x,z]of[['↑',0,-12],['↓',0,12],['←',-12,0],['→',12,0]])c.fillText(text,p.x+x,p.z+z);
  }
  c.globalAlpha=.16;c.lineWidth=.5;c.beginPath();c.roundRect(-37.5,-28,75,65,2);c.stroke();c.restore();
}

class ThorHotkeyLayers extends HTMLElement{
  static observedAttributes=['mode'];
  connectedCallback(){
    if(this.controller)return;
    try{this.controller=this.mount();this.controller.setMode(this.getAttribute('mode'),true);}
    catch(error){this.controller?.dispose();this.replaceChildren();this.closest('.playground').dataset.ready='false';console.warn('Using device illustration:',error.message);}
  }
  disconnectedCallback(){this.controller?.dispose();this.controller=null;}
  attributeChangedCallback(){this.controller?.setMode(this.getAttribute('mode'));}
  mount(){
    const host=this,playground=this.closest('.playground');
    const renderer=new T.WebGLRenderer({alpha:true,antialias:true});renderer.setPixelRatio(Math.min(devicePixelRatio,1.5));
    renderer.toneMapping=T.ACESFilmicToneMapping;renderer.toneMappingExposure=1.1;
    const scene=new T.Scene(),thor=createThor();thor.setAngle(150);scene.add(thor.root);
    scene.add(new T.HemisphereLight('#ffffff','#69717b',2));
    for(const [x,y,z,power]of[[-150,230,160,2.5],[180,110,-100,1.7]]){const light=new T.DirectionalLight('#ffffff',power);light.position.set(x,y,z);scene.add(light);}
    const targets=new Map(CONTROLS.map(control=>[control.name,controlAnchor(T,thor.root,control)]));
    targets.set('back',controlAnchor(T,thor.root,{joint:'back'}));
    const caps=['back','home'].map(name=>{const mesh=thor.root.getObjectByName(name+'-button');mesh.material=mesh.material.clone();return {name,mesh,original:mesh.material.color.clone()};});
    // The film's neutral contour view keeps the physical inputs legible beneath the layers.
    const frontJoints=['volume','power'].map(name=>thor.root.getObjectByName(name+'-joint'));
    frontJoints.forEach(joint=>{joint.userData.inputControl=null;});
    const design=createDesignMode(thor.root);design.setSolidControls(true);design.setEnabled(true);
    frontJoints.forEach((joint,i)=>{joint.userData.inputControl=['volume','power'][i];});
    thor.root.traverse(object=>{for(const material of Array.isArray(object.material)?object.material:[object.material]){
      if(material?.uniforms?.color)material.uniforms.color.value.set('#79868f');
      if(material?.color?.getHexString()==='101f27')material.color.set('#172129');
      if(material?.color?.getHexString()==='65becd')material.color.set('#79868f');
    }});
    const panes=[PINK,GREEN].map(color=>{
      const group=new T.Group();scene.add(group);
      const canvas=document.createElement('canvas');canvas.width=1068;canvas.height=630;
      const texture=new T.CanvasTexture(canvas);texture.colorSpace=T.SRGBColorSpace;texture.generateMipmaps=false;texture.minFilter=T.LinearFilter;
      const geometry=new T.ShapeGeometry(roundedOutline(178,105,13));
      const uv=geometry.attributes.uv,p=geometry.attributes.position;for(let i=0;i<uv.count;i++)uv.setXY(i,p.getX(i)/178+.5,p.getY(i)/105+.5);
      const plane=new T.Mesh(geometry,new T.MeshBasicMaterial({color,transparent:true,opacity:.1,side:T.DoubleSide,depthTest:false,depthWrite:false,toneMapped:false}));plane.rotation.x=-Math.PI/2;plane.renderOrder=20;group.add(plane);
      const path=roundedOutline(178,105,13).getPoints(80).map(point=>new T.Vector3(point.x,0,-point.y));
      const edge=new T.LineLoop(new T.BufferGeometry().setFromPoints(path),new T.LineBasicMaterial({color,transparent:true,depthTest:false,depthWrite:false,toneMapped:false}));edge.renderOrder=21;group.add(edge);
      const layout=new T.Mesh(geometry,new T.MeshBasicMaterial({map:texture,transparent:true,depthTest:false,depthWrite:false,toneMapped:false}));layout.rotation.x=-Math.PI/2;layout.position.y=.1;layout.renderOrder=22;group.add(layout);
      return {group,canvas,texture,plane,edge,layout,color};
    });
    const leaders=['back','home'].map(name=>{
      const geometry=new T.BufferGeometry().setFromPoints([new T.Vector3(),new T.Vector3()]);
      const line=new T.Line(geometry,new T.LineBasicMaterial({color:PINK,transparent:true,depthTest:false,depthWrite:false,toneMapped:false}));line.renderOrder=23;scene.add(line);return {name,line};
    });
    const pose=cameraPose(16.4),camera=new T.OrthographicCamera(-150,150,150,-150,1,1800);
    const bounds=new T.Box3().setFromObject(thor.root);bounds.expandByPoint(new T.Vector3(-96,HOME_HEIGHT,-60));bounds.expandByPoint(new T.Vector3(96,HOME_HEIGHT,60));
    const center=bounds.getCenter(new T.Vector3());
    camera.position.copy(center).add(new T.Vector3(...pose.position).sub(new T.Vector3(...pose.target)));camera.lookAt(center);camera.updateMatrixWorld();
    const corners=[];for(const x of[bounds.min.x,bounds.max.x])for(const y of[bounds.min.y,bounds.max.y])for(const z of[bounds.min.z,bounds.max.z])corners.push(new T.Vector3(x,y,z).applyMatrix4(camera.matrixWorldInverse));
    const projected=new T.Box3().setFromPoints(corners);
    const label=(text,className)=>{const node=document.createElement('span');node.className=className;node.textContent=text;node.setAttribute('aria-hidden','true');return node;};
    const triggerLabels=['back','home'].map(name=>({name,node:label(name==='back'?'Back':'Home','layer-trigger '+name)}));
    const layerLabels=['back','home'].map(name=>({name,node:label(name==='back'?'Back hotkeys':'Home hotkeys','layer-name '+name)}));
    renderer.domElement.setAttribute('aria-hidden','true');host.replaceChildren(renderer.domElement,...triggerLabels.map(item=>item.node),...layerLabels.map(item=>item.node));
    host.setAttribute('role','img');
    let current={back:0,home:0,homeEnabled:0,independent:0},target=frameForMode(host.getAttribute('mode'));
    let animation,disposed=false,paintedHome=null;
    const reduced=matchMedia('(prefers-reduced-motion: reduce)');
    function place(node,position){
      const p=position.clone().project(camera),w=host.clientWidth,h=host.clientHeight;
      const x=(p.x+1)*w/2,y=(1-p.y)*h/2;
      node.style.left=`${Math.max(node.offsetWidth/2+4,Math.min(w-node.offsetWidth/2-4,x))}px`;
      node.style.top=`${Math.max(20,Math.min(h-24,y))}px`;
    }
    function render(){
      if(disposed)return;
      const homeY=BACK_HEIGHT+(HOME_HEIGHT-BACK_HEIGHT)*current.home;
      const homeColor=new T.Color(PINK).lerp(new T.Color(GREEN),current.independent);
      thor.inputs.releaseAll();thor.inputs.setButton('back',target.id==='back'?current.back:0);thor.inputs.setButton('home',current.homeEnabled);
      for(const cap of caps){const enabled=cap.name==='back'?current.back:current.homeEnabled;const color=cap.name==='back'?new T.Color(PINK):homeColor;cap.mesh.material.color.copy(cap.original).lerp(color,enabled*.7);cap.mesh.material.emissive.copy(color);cap.mesh.material.emissiveIntensity=enabled*.3;}
      panes.forEach((pane,i)=>{const amount=i?current.home:current.back;pane.group.position.y=i?homeY:9+(BACK_HEIGHT-9)*current.back;pane.group.visible=amount>.001;pane.plane.material.opacity=amount*.11;pane.edge.material.opacity=amount*.95;pane.layout.material.opacity=amount;});
      const homeIsModifier=target.homeEnabled>0;
      if(paintedHome!==homeIsModifier){for(const pane of panes){paintDeck(pane.canvas,targets,pane.color,homeIsModifier);pane.texture.needsUpdate=true;}paintedHome=homeIsModifier;}
      for(const {name,line}of leaders){const home=name==='home',start=targets.get(name);const height=home&&current.independent>.001?homeY:panes[0].group.position.y;const attr=line.geometry.attributes.position;attr.setXYZ(0,start.x,start.y,start.z);attr.setXYZ(1,start.x,height,start.z);attr.needsUpdate=true;line.geometry.computeBoundingSphere();line.material.color.copy(home?homeColor:new T.Color(PINK));line.material.opacity=home?current.homeEnabled:current.back;}
      renderer.render(scene,camera);
      for(const item of triggerLabels){item.node.hidden=item.name==='home'&&current.homeEnabled<.02;item.node.classList.toggle('independent',item.name==='home'&&current.independent>.5);place(item.node,targets.get(item.name));}
      for(const [i,item]of layerLabels.entries()){item.node.hidden=i===1&&current.home<.02;item.node.style.opacity=String(i?current.home:current.back);item.node.textContent=i?'Home hotkeys':target.id==='shared'?'Shared hotkeys':'Back hotkeys';place(item.node,new T.Vector3(-30,i?homeY:panes[0].group.position.y,54));}
      host.dataset.mode=target.id;host.dataset.layers=target.home?'2':'1';host.dataset.settled=String(PARTS.every(key=>Math.abs(current[key]-target[key])<.001));
    }
    function resize(){const w=host.clientWidth,h=host.clientHeight;if(!w||!h||disposed)return;renderer.setSize(w,h);design.setViewport(w,h,renderer.getPixelRatio());const aspect=w/h,half=Math.max((projected.max.y-projected.min.y)/2,(projected.max.x-projected.min.x)/2/aspect)*1.15,x=(projected.min.x+projected.max.x)/2,y=(projected.min.y+projected.max.y)/2;camera.left=x-half*aspect;camera.right=x+half*aspect;camera.bottom=y-half;camera.top=y+half;camera.updateProjectionMatrix();render();}
    function settle(){cancelAnimationFrame(animation);current=Object.fromEntries(PARTS.map(key=>[key,target[key]]));render();}
    function setMode(mode,immediate=false){
      cancelAnimationFrame(animation);target=frameForMode(mode);host.setAttribute('aria-label',target.description);
      if(immediate||reduced.matches||document.hidden){settle();return;}
      const start={...current},began=performance.now();host.dataset.settled='false';
      function tick(now){if(disposed)return;const progress=Math.min(1,(now-began)/420),t=ease(progress);for(const key of PARTS)current[key]=start[key]+(target[key]-start[key])*t;render();if(progress<1)animation=requestAnimationFrame(tick);}
      animation=requestAnimationFrame(tick);
    }
    resize();playground.dataset.ready='true';
    const observer=new ResizeObserver(resize);observer.observe(host);
    const visibility=()=>{if(document.hidden)settle();};document.addEventListener('visibilitychange',visibility);
    const preference=()=>{if(reduced.matches)settle();};reduced.addEventListener('change',preference);
    renderer.domElement.addEventListener('webglcontextlost',event=>{event.preventDefault();cancelAnimationFrame(animation);playground.dataset.ready='false';});
    renderer.domElement.addEventListener('webglcontextrestored',()=>{settle();resize();playground.dataset.ready='true';});
    return {setMode,dispose(){disposed=true;cancelAnimationFrame(animation);observer.disconnect();document.removeEventListener('visibilitychange',visibility);reduced.removeEventListener('change',preference);design.dispose();const geometries=new Set(),materials=new Set(),textures=new Set();scene.traverse(object=>{if(object.geometry)geometries.add(object.geometry);for(const material of Array.isArray(object.material)?object.material:[object.material])if(material){materials.add(material);if(material.map)textures.add(material.map);}});for(const pane of panes)textures.add(pane.texture);for(const g of geometries)g.dispose();for(const m of materials)m.dispose();for(const t of textures)t.dispose();renderer.dispose();}};
  }
}
if(!customElements.get('thor-hotkey-layers'))customElements.define('thor-hotkey-layers',ThorHotkeyLayers);
