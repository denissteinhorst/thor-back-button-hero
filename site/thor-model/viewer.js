import { createHotkeyLabels } from './intro-hotkey-demo.js?v=ad-1';
import { createPauseHighlights } from './intro-pause-demo.js?v=ad-1';
// SPDX-License-Identifier: 0BSD
import * as THREE from 'three';
import { OrbitControls } from './vendor/OrbitControls.js';
import { createThor } from './thor.js';
import { updateStudioKey } from './lighting.js';
import { mountInputInspector } from './input-inspector.js';
import { snapHingeAngle } from './hinge.js';
import { createDesignMode } from './design-mode.js?v=radial-1';
import { mountPoseInspector } from './pose-inspector.js';
import { createIntroScreens } from './intro-screens.js?v=ad-1';
import { mountIntroPlayer } from './intro-player.js?v=ad-1';
import { createIntroWireframe } from './intro-wireframe.js?v=ad-1';
import { createBackHoldHint } from './intro-back-hint.js?v=ad-1';
import { createRadialDemo } from './intro-radial.js?v=ad-1';

const viewport=document.getElementById('viewport');
const status=document.getElementById('status');
try {
  const renderer=new THREE.WebGLRenderer({antialias:true,alpha:true});
  renderer.setPixelRatio(Math.min(devicePixelRatio,2));
  renderer.shadowMap.enabled=true;renderer.shadowMap.type=THREE.PCFSoftShadowMap;
  renderer.toneMapping=THREE.ACESFilmicToneMapping;renderer.toneMappingExposure=1.05;
  viewport.append(renderer.domElement);
  const scene=new THREE.Scene();
  const camera=new THREE.PerspectiveCamera(33,1,1,1500);
  const ortho=new THREE.OrthographicCamera(-100,100,100,-100,1,1500);
  camera.position.set(185,205,235);
  const controls=new OrbitControls(camera,renderer.domElement);
  controls.target.set(0,29,-20);controls.enableDamping=true;
  controls.minDistance=140;controls.maxDistance=650;controls.minZoom=.55;controls.maxZoom=3;
  controls.enablePan=false;controls.listenToKeyEvents(viewport);
  // Original neutral studio cards generate reflections; no HDRI or texture assets.
  const studio=new THREE.Scene();studio.background=new THREE.Color('#626768');
  for(const [w,h,pos,target,power] of [
    [240,180,[-140,190,120],[0,0,0],4],
    [170,220,[180,100,-170],[0,0,0],2.5],
    [90,250,[0,60,210],[0,0,0],1.7]
  ]){
    const card=new THREE.Mesh(new THREE.PlaneGeometry(w,h),new THREE.MeshBasicMaterial({color:new THREE.Color().setScalar(power)}));
    card.position.set(...pos);card.lookAt(...target);studio.add(card);
  }
  const pmrem=new THREE.PMREMGenerator(renderer);
  const environment=pmrem.fromScene(studio,.04);scene.environment=environment.texture;scene.environmentIntensity=.55;
  pmrem.dispose();studio.traverse(o=>{if(o.isMesh){o.geometry.dispose();o.material.dispose();}});
  scene.add(new THREE.HemisphereLight('#e6edf1','#6a6255',1.3));
  function light(color,power,x,y,z,shadow=false) {
    const l=new THREE.DirectionalLight(color,power);l.position.set(x,y,z);scene.add(l);
    if(shadow){l.castShadow=true;l.shadow.mapSize.set(2048,2048);Object.assign(l.shadow.camera,{left:-170,right:170,top:190,bottom:-170,near:1,far:650});l.shadow.normalBias=.08;l.shadow.bias=-.00005;}
    return l;
  }
  const keyLight=light('#fff4df',3,70,240,150,true);
  scene.add(keyLight.target);
  // The studio's overhead cards cannot illuminate a downward-facing back. A
  // soft viewing-axis fill makes its moulded domes readable during inspection.
  const undersideFill=light('#f2f3ec',0,0,-150,0);scene.add(undersideFill.target);
  light('#d4e3ed',.9,-160,100,-110);light('#ffffff',1.4,0,80,-200);
  const thor=createThor();scene.add(thor.root);
  window.thorInputs=thor.inputs;
  mountInputInspector(thor.inputs);
  let floor,introScreens=null;
  const design=createDesignMode(thor.root);
  const designToggle=document.getElementById('design-toggle'),solidControls=document.getElementById('design-solid-controls');
  const designBadge=document.getElementById('design-badge');
  function designUi() {
    const state=design.getState();
    designToggle.setAttribute('aria-pressed',String(state.enabled));
    solidControls.disabled=!state.enabled;
    viewport.classList.toggle('design-mode',state.enabled);designBadge.hidden=!state.enabled;
    introScreens?.setWireWatermarkVisible(state.enabled);
    if(floor)floor.visible=floor.userData.viewVisible&&!state.enabled;
  }
  designToggle.addEventListener('click',()=>{design.setEnabled(!design.getState().enabled);designUi();});
  solidControls.addEventListener('change',()=>design.setSolidControls(solidControls.checked));
  designUi();
  floor=new THREE.Mesh(new THREE.PlaneGeometry(2000,2000),new THREE.ShadowMaterial({opacity:.2}));floor.userData.viewVisible=true;
  floor.rotation.x=-Math.PI/2;floor.position.y=-11;floor.receiveShadow=true;scene.add(floor);
  function resize(){
    const {width,height}=viewport.getBoundingClientRect();if(width<=0||height<=0)return;renderer.setSize(width,height);
    design.setViewport(width,height,renderer.getPixelRatio());
    const aspect=width/height;camera.aspect=aspect;camera.updateProjectionMatrix();
    const half=Math.max(96,92/aspect);ortho.left=-half*aspect;ortho.right=half*aspect;ortho.top=half;ortho.bottom=-half;ortho.updateProjectionMatrix();
  }
  new ResizeObserver(resize).observe(viewport);resize();
  const slider=document.getElementById('angle'),output=document.getElementById('angle-value');
  const modelBounds=new THREE.Box3();
  let hingeAngle=150;
  let detailFocus=null;
  function focusCentre(){return detailFocus?new THREE.Vector3(...detailFocus):modelBounds.setFromObject(thor.root).getCenter(new THREE.Vector3());}
  function angle(value,updateSlider=true){
    hingeAngle=value;
    thor.setAngle(value);if(updateSlider)slider.value=String(value);output.value=value+'°';
    const detentButtons=[...document.querySelectorAll('[data-hinge-angle]')];
    const detent=detentButtons.findIndex(b=>Number(b.dataset.hingeAngle)===value);
    slider.setAttribute('aria-valuetext',value+' Grad'+(detent<0?'':', Rastung '+(detent+1)));
    detentButtons.forEach(b=>b.setAttribute('aria-pressed',String(Number(b.dataset.hingeAngle)===value)));
    const centre=focusCentre(),delta=centre.clone().sub(controls.target);
    controls.object.position.add(delta);controls.target.copy(centre);controls.update();
  }
  const views={
    perspective:{offset:[185,176,255]},
    front:{offset:[0,360,.1],angle:180,orthographic:true},
    rear:{offset:[-155,101,-240]},
    underside:{offset:[0,-310,.01],orthographic:true},
    shoulder:{offset:[-145,-150,-180],focus:[-63,-6,-38],angle:120,orthographic:true,zoom:2.3}
  };
  for(const [id,view] of Object.entries(views))document.getElementById(id).addEventListener('click',()=>{
    detailFocus=view.focus??null;
    controls.object=view.orthographic?ortho:camera;
    if(view.angle!==undefined)angle(view.angle);
    const centre=focusCentre();
    controls.object.position.set(...view.offset).add(centre);controls.object.zoom=view.zoom??1;controls.object.updateProjectionMatrix();
    controls.target.copy(centre);floor.userData.viewVisible=id!=='underside'&&id!=='shoulder';
    floor.visible=floor.userData.viewVisible&&!design.getState().enabled;controls.update();
    document.querySelectorAll('.views button').forEach(b=>b.setAttribute('aria-pressed',String(b.id===id)));
  });
  controls.addEventListener('start',()=>document.querySelectorAll('.views button').forEach(b=>b.setAttribute('aria-pressed','false')));
  // Keep the raw thumb position while snapping the model. Writing the snapped
  // value back here would trap keyboard arrows inside a detent's capture zone.
  slider.addEventListener('input',event=>angle(snapHingeAngle(Number(event.target.value)),false));
  for(const button of document.querySelectorAll('[data-hinge-angle]'))button.addEventListener('click',()=>angle(Number(button.dataset.hingeAngle)));
  angle(150);
  let introPlayer=null,introFrame=null,introRender=null;
  function applyIntroCamera(frame) {
    const view=frame.cameraType==='orthographic'?ortho:camera;
    Object.assign(ortho,frame.orthoFrustum);ortho.near=1;ortho.far=1500;ortho.zoom=frame.viewZoom??1;ortho.updateProjectionMatrix();
    camera.fov=33;camera.zoom=1;camera.near=1;camera.far=1500;camera.updateProjectionMatrix();
    if(frame.projectionMorph>0&&frame.projectionMorph<1){
      // Normalize perspective W at the focus plane before blending projections.
      // At the endpoint the matrix exactly matches the real orthographic camera.
      const depth=Math.hypot(...frame.cameraPosition.map((v,i)=>v-frame.cameraTarget[i]));
      const a=camera.projectionMatrix.elements,b=ortho.projectionMatrix.elements,p=frame.projectionMorph;
      for(let i=0;i<16;i++)a[i]=a[i]/depth*(1-p)+b[i]*p;
      camera.projectionMatrixInverse.copy(camera.projectionMatrix).invert();
    }
    view.position.fromArray(frame.cameraPosition);view.quaternion.fromArray(frame.cameraQuaternion);view.up.set(0,1,0);view.updateMatrixWorld(true);
    controls.object=view;
  }
  createIntroScreens(thor,renderer).then(screens=>{
    introScreens=screens;
    const radial=createRadialDemo(thor,scene,renderer);
    const pauseHighlights=createPauseHighlights(thor);
    const hotkeyLabels=createHotkeyLabels(scene,renderer);
    const wire=createIntroWireframe(renderer,scene,camera,thor,design,floor,()=>radial.decorate());
    const hint=createBackHoldHint(thor,scene,renderer);
    const homeHint=createBackHoldHint(thor,scene,renderer,{key:'home',text:'HOLD Home',subtitle:'emulating Back'});
    introRender=()=>{applyIntroCamera(introFrame);hotkeyLabels.update(introFrame,controls.object);wire.render(introFrame,controls.object);};
    let saved;
    const modelControls=[...document.querySelectorAll('.views button,.hinge-control input,.hinge-control button,.design-tools input,.design-tools button,.input-inspector input,.input-inspector select,.input-inspector button')];
    introPlayer=mountIntroPlayer({
      onEnter(){
        saved={active:controls.object,activeState:controls.object.clone(false),camera:new THREE.PerspectiveCamera().copy(camera,false),target:controls.target.clone(),
          angle:hingeAngle,focus:detailFocus,design:design.getState(),floor:floor.userData.viewVisible,
          inputs:thor.inputs.getState(),disabled:modelControls.map(element=>element.disabled)};
        // Flush OrbitControls inertia before a deterministic timeline takes over.
        controls.enableDamping=false;controls.update();controls.enableDamping=true;controls.enabled=false;
        modelControls.forEach(element=>element.disabled=true);
        design.setEnabled(false);designUi();thor.inputs.releaseAll();
        floor.userData.viewVisible=true;floor.visible=true;
        document.querySelector('main').classList.add('intro-active');viewport.classList.add('intro-mode');resize();
      },
      onLeave(){
        radial.reset();hotkeyLabels.hide();pauseHighlights.hide();wire.reset();hint.hide();homeHint.hide();
        screens.update(null,false);
        document.querySelector('main').classList.remove('intro-active');viewport.classList.remove('intro-mode');
        viewport.setAttribute('aria-label','Drehbares 3D-Modell des AYN Thor Rainbow');
        camera.copy(saved.camera,false);controls.object=saved.active;detailFocus=saved.focus;
        angle(saved.angle);controls.target.copy(saved.target);
        // angle() normally re-centres the view; restore the saved camera after it.
        camera.copy(saved.camera,false);
        saved.active.copy(saved.activeState,false);
        floor.userData.viewVisible=saved.floor;design.setSolidControls(saved.design.solidControls);design.setSolidControl(saved.design.solidControl??null);design.setSolidControlNames(saved.design.solidControlNames??[]);design.setEnabled(saved.design.enabled);designUi();
        for(const [name,pressure] of Object.entries(saved.inputs.buttons))thor.inputs.setButton(name,pressure);
        for(const [side,state] of Object.entries(saved.inputs.sticks))thor.inputs.setStick(side,state);
        modelControls.forEach((element,index)=>element.disabled=saved.disabled[index]);
        controls.enabled=true;controls.update();resize();
      },
      onFrame(frame){
        introFrame=frame;
        hingeAngle=frame.hingeAngle;thor.setAngle(hingeAngle);
        const buttons=thor.inputs.getState().buttons;
        for(const [key,pressure] of Object.entries(frame.buttonPressures))if(buttons[key]!==pressure)thor.inputs.setButton(key,pressure);
        const stickState=thor.inputs.getState().sticks;
        for(const [side,pose] of Object.entries(frame.stickPoses)){
          if(stickState[side].x!==pose.x||stickState[side].y!==pose.y)thor.inputs.setStick(side,pose);
        }
        pauseHighlights.update(frame);
        slider.value=String(hingeAngle);output.value=hingeAngle.toFixed(2)+'°';
        applyIntroCamera(frame);
        Object.assign(frame,radial.update(frame,controls.object));
        controls.target.fromArray(frame.cameraTarget);screens.update(frame,frame.wireProgress<1);hint.update(frame,controls.object);homeHint.update(frame,controls.object);
        viewport.setAttribute('aria-label','Intro-Vorschau: '+frame.phase+', Scharnier '+hingeAngle.toFixed(2)+' Grad');
      }
    });
  }).catch(error=>{
    document.getElementById('intro-load-status').textContent='Intro konnte nicht geladen werden. Bitte Seite neu laden.';console.error(error);
  });
  const updatePose=mountPoseInspector(()=>{
    const active=controls.object;
    return {
      schema:'thor-viewer-pose-v1',
      units:{position:'mm',angle:'degrees',quaternion:'[x, y, z, w]'},
      camera:{
        type:active.isOrthographicCamera?'orthographic':'perspective',
        position:active.position.toArray(),quaternion:active.quaternion.toArray(),
        up:active.up.toArray(),target:controls.target.toArray(),zoom:active.zoom,
        near:active.near,far:active.far,
        ...(active.isOrthographicCamera?
          {left:active.left,right:active.right,top:active.top,bottom:active.bottom}:
          {fov:active.fov,aspect:active.aspect})
      },
      model:{position:thor.root.position.toArray(),quaternion:thor.root.quaternion.toArray(),scale:thor.root.scale.toArray(),hingeAngle},
      viewport:{width:renderer.domElement.clientWidth,height:renderer.domElement.clientHeight},
      design:design.getState(),inputs:thor.inputs.getState(),
      ...(introPlayer?.isEnabled()?{animation:{time:introFrame.time,wireProgress:introFrame.wireProgress,backPressure:introFrame.backPressure,radialProgress:introFrame.radialProgress,reachFlash:introFrame.reachFlash}}:{})
    };
  });
  renderer.domElement.addEventListener('webglcontextlost',event=>{event.preventDefault();status.hidden=false;status.textContent='3D-Kontext unterbrochen. Bitte Seite neu laden.';});
  renderer.setAnimationLoop(now=>{
    introPlayer?.tick(now);
    if(!introPlayer?.isEnabled())controls.update();
    updateStudioKey(keyLight,controls.object,controls.target);
    undersideFill.position.copy(controls.object.position);undersideFill.target.position.copy(controls.target);
    undersideFill.intensity=1.5*THREE.MathUtils.smoothstep(controls.target.y-controls.object.position.y,0,150);
    undersideFill.target.updateMatrixWorld();
    if(introPlayer?.isEnabled())introRender();else renderer.render(scene,controls.object);
    updatePose();
  });
  status.hidden=true;
}catch(error){status.textContent='3D-Vorschau konnte nicht starten. Bitte WebGL im Browser aktivieren.';console.error(error);}
