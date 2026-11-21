// SPDX-License-Identifier: 0BSD
import {registerHooks} from 'node:module';
import assert from 'node:assert/strict';
registerHooks({resolve(specifier,context,next){return next(specifier==='three'?new URL('../model-editor/vendor/three.module.js',import.meta.url).href:specifier,context);}});
const T=await import('three');
const {createThor}=await import('../model-editor/thor.js');
const {sample}=await import('./timeline.js');
const {cameraPose,smooth}=await import('./motion.js');
const {CONTROLS}=await import('./controls.js');
const {createDesignMode}=await import('../model-editor/design-mode.js');
const thor=createThor({markTexture:()=>null});thor.setAngle(150);
assert.equal(CONTROLS.filter(c=>c.joint==='dpad').length,4);
const front=['volume','power'].map(name=>thor.root.getObjectByName(name+'-joint'));
front.forEach(joint=>joint.userData.inputControl=null);
const design=createDesignMode(thor.root);front.forEach((joint,i)=>joint.userData.inputControl=['volume','power'][i]);
const rocker=thor.root.getObjectByName('volume-rocker'),power=thor.root.getObjectByName('power-button'),sd=thor.root.getObjectByName('microsd-cover'),button=thor.root.getObjectByName('button-A');
const original=[rocker.material,power.material,button.material];design.setEnabled(true);
assert.equal(rocker.material,sd.material);assert.equal(power.material,sd.material);assert.equal(button.material,original[2]);
design.setEnabled(false);assert.equal(rocker.material,original[0]);assert.equal(power.material,original[1]);design.dispose();

const camera=new T.OrthographicCamera();camera.near=1;camera.far=1600;
let minLayerSeparation=Infinity;
for(let frame=264;frame<1140;frame++){
 const t=frame/60,pose=cameraPose(t);
 camera.position.fromArray(pose.position);camera.lookAt(...pose.target);camera.left=-pose.half*720/860;camera.right=-camera.left;camera.top=pose.half;camera.bottom=-pose.half;camera.updateProjectionMatrix();camera.updateMatrixWorld();
 for(const layer of [0,1]){
  if(sample(t).panes[layer]<=0)continue;
  for(const x of [-89,89])for(const z of [-52.5,52.5]){
   const p=new T.Vector3(x,layer?24+30*smooth((t-15.05)/1.15):24,z).project(camera);
   assert.ok(Math.abs(p.x)<.98&&Math.abs(p.y)<.98,`Layout pane clipped at ${t}`);
  }
 }
 if(t>=16&&t<19){const a=new T.Vector3(0,24,0).project(camera),b=new T.Vector3(0,54,0).project(camera);minLayerSeparation=Math.min(minLayerSeparation,a.distanceTo(b)*430);}
}
assert.ok(minLayerSeparation>70);
assert.ok(cameraPose(4.5).half<23,'Opening press must be a close-up');
for(let frame=0;frame<=1800;frame++){
 const s=sample(frame/60);
 assert.ok(Object.keys(s.buttons).every(key=>['back','home'].includes(key)),'Only modifier buttons may be pressed');
}
assert.equal(sample(4.4).buttons.back,0);
assert.ok(sample(4.85).buttons.back>.9);
assert.equal(sample(4.85).panes[0],0);
assert.ok(cameraPose(4.85).half<23,'Keep the real press in the close-up');
assert.equal(sample(5.7).panes[0],0);
assert.ok(sample(14.8).panes[0]>.99,'First layer rests before the second rises');
assert.equal(sample(3.5).panes[0],0);
assert.equal(sample(8).panes[0],0);
assert.ok(sample(9).sharedHome>.9);
assert.equal(sample(9).panes[0],0);
assert.equal(sample(13).home,0);
assert.ok(sample(14.8).buttons.home>.9);
assert.equal(sample(14.8).panes[1],0);
assert.ok(sample(16.3).panes[1]>.99);
for(const t of [19,20,21,22,23,24,25,26,27,28,30])assert.deepEqual(sample(t),sample(t));
assert.equal(sample(3.3).wire,0);assert.equal(sample(19.5).wire,0);
assert.equal(sample(19).record,false);
console.log(`Pane corners stay in frame throughout the layout demonstration; layers separate by at least ${minLayerSeparation.toFixed(1)} px. Feature sequence seeking is deterministic.`);

// Record compositing commands to protect the reveal order and the two-second
// reading hold without requiring a GPU or pretending to certify visual quality.
const {draw}=await import('./graphics.js');
function commands(t){
 const ops=[],stack=[];
 const c={globalAlpha:1,font:'10px Arial',fillStyle:'#000',textAlign:'left',textBaseline:'alphabetic',letterSpacing:'0px'};
 const state=()=>Object.fromEntries(Object.entries(c).filter(([,value])=>typeof value!=='function'));
 c.save=()=>stack.push(state());c.restore=()=>Object.assign(c,stack.pop());
 for(const method of ['clearRect','fillRect','beginPath','roundRect','fill','rect','clip','translate','scale','arc','stroke','moveTo','lineTo'])c[method]=(...args)=>ops.push([method,...args,state()]);
 c.fillText=(...args)=>ops.push(['text',...args,state()]);
 c.drawImage=(image,...args)=>ops.push(['image',image,...args,state()]);
 c.createLinearGradient=c.createRadialGradient=()=>({addColorStop(){}});
 c.measureText=value=>({width:value.length*Number(c.font.match(/([\d.]+)px/)[1])*.53});
 const studio={point(){return {x:360,y:800};},render(){ops.push(['render']);return 'product';}};
 draw(c,sample(t),studio,'logo');return JSON.parse(JSON.stringify(ops));
}
const visibleText=t=>commands(t).filter(op=>op[0]==='text'&&op.at(-1).globalAlpha>.01).map(op=>op[1]);
assert.ok(!visibleText(.7).includes('2.0'),'Material comes before the version');
assert.ok(visibleText(1.6).includes('2.0'));
assert.ok(!visibleText(1.6).includes('Built from your feedback.'));
assert.ok(visibleText(2.25).includes('Built from your feedback.'));
assert.ok(visibleText(3.5).includes('Back Button Hero'),'Give the saved close-up time to the intro');
assert.ok(visibleText(3.5).includes('2.0'));
assert.ok(cameraPose(3.5).half>100,'Stay on the complete product during the extended intro');
assert.ok(!commands(2).some(op=>op[0]==='image'&&op[1]==='logo'),'No competing intro badge');
for(const t of [6.5,10.5,15.8])assert.ok(!visibleText(t).some(value=>value.includes('38')));
assert.ok(visibleText(15.8).includes('Two independent'));
assert.ok(commands(27.4).some(op=>op[0]==='render'));
assert.ok(!commands(27.55).some(op=>op[0]==='render'),'Product leaves before the card starts');
for(const t of [28.5,29,29.5,30])assert.deepEqual(commands(t),commands(28),'Endcard holds unchanged from 28 to 30 seconds');
console.log('Reveal order, state-specific headlines, product exit and static endcard compositing pass.');
