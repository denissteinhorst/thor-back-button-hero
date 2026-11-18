// SPDX-License-Identifier: 0BSD
import * as THREE from 'three';

export const BUTTON_NAMES=Object.freeze([
  'A','B','X','Y','select','start','home','back','AYN','power',
  'volumeDown','volumeUp','L1','R1','L2','R2','L3','R3',
  'dpadUp','dpadDown','dpadLeft','dpadRight'
]);

const radians=THREE.MathUtils.degToRad;
function finite(value,label,min,max) {
  if(typeof value!=='number'||!Number.isFinite(value))throw new TypeError(label+' must be a finite number');
  return THREE.MathUtils.clamp(value,min,max);
}
function direction(x,y) {
  const length=Math.hypot(x,y);return length>1?{x:x/length,y:y/length}:{x,y};
}

// Setters apply an absolute pose immediately. Animation callers can supply any
// intermediate pressure/axis value; the renderer does not own a second input state.
export function createInputRig(root,joints) {
  const buttons=Object.fromEntries(BUTTON_NAMES.map(name=>[name,0]));
  const sticks={left:{x:0,y:0},right:{x:0,y:0}};
  const subscribers=new Set();
  const rest=new Map();
  for(const joint of Object.values(joints))rest.set(joint,{position:joint.position.clone(),quaternion:joint.quaternion.clone()});
  const rotation=new THREE.Quaternion(), axis=new THREE.Vector3();
  const move=(name,x,y,z)=>{const joint=joints[name];joint.position.copy(rest.get(joint).position).add(new THREE.Vector3(x,y,z));};
  const turn=(name,x,y,z,angle)=>{
    const joint=joints[name];axis.set(x,y,z).normalize();rotation.setFromAxisAngle(axis,angle);
    joint.quaternion.copy(rest.get(joint).quaternion).multiply(rotation);
  };
  const dpad=()=>direction(buttons.dpadRight-buttons.dpadLeft,buttons.dpadUp-buttons.dpadDown);
  function tilt(name,x,y,limit) {
    const amount=Math.hypot(x,y);
    if(amount)turn(name,-y,0,-x,radians(limit)*amount);
    else joints[name].quaternion.copy(rest.get(joints[name]).quaternion);
  }
  function apply() {
    for(const name of ['A','B','X','Y'])move(name,0,-.72*buttons[name],0);
    for(const name of ['select','start','home','back'])move(name,0,-.32*buttons[name],0);
    move('AYN',0,-.28*buttons.AYN,0);
    move('power',0,0,-.32*buttons.power);
    turn('volume',0,1,0,radians(3.6)*(buttons.volumeUp-buttons.volumeDown));
    for(const name of ['L1','R1'])move(name,0,0,.55*buttons[name]);
    for(const name of ['L2','R2'])turn(name,1,0,0,-radians(18)*buttons[name]);
    const dp=dpad();tilt('dpad',dp.x,dp.y,5);
    for(const side of ['left','right']) {
      const name=side==='left'?'L3':'R3';
      move(side+'Stick',0,-.45*buttons[name],0);
      tilt(side+'Stick',sticks[side].x,sticks[side].y,16);
    }
    root.updateMatrixWorld(true);
    for(const listener of subscribers)listener(api.getState());
  }
  const api={
    buttons:BUTTON_NAMES,
    setButton(name,value=1) {
      if(!Object.hasOwn(buttons,name))throw new RangeError('Unknown button: '+name);
      const pressure=typeof value==='boolean'?Number(value):finite(value,name,0,1);
      buttons[name]=pressure;apply();return api;
    },
    setDpad(x,y) {
      const next=direction(finite(x,'D-pad x',-1,1),finite(y,'D-pad y',-1,1));
      buttons.dpadLeft=Math.max(0,-next.x);buttons.dpadRight=Math.max(0,next.x);
      buttons.dpadUp=Math.max(0,next.y);buttons.dpadDown=Math.max(0,-next.y);
      apply();return api;
    },
    setStick(side,pose) {
      if(!Object.hasOwn(sticks,side))throw new RangeError('Unknown stick: '+side);
      if(!pose||typeof pose!=='object')throw new TypeError('Stick pose must be an object');
      const next=direction(
        pose.x===undefined?sticks[side].x:finite(pose.x,'Stick x',-1,1),
        pose.y===undefined?sticks[side].y:finite(pose.y,'Stick y',-1,1)
      );
      const click=side==='left'?'L3':'R3';
      const press=pose.press===undefined?buttons[click]:typeof pose.press==='boolean'?Number(pose.press):finite(pose.press,'Stick press',0,1);
      sticks[side]=next;buttons[click]=press;apply();return api;
    },
    releaseAll() {
      for(const name of BUTTON_NAMES)buttons[name]=0;
      sticks.left={x:0,y:0};sticks.right={x:0,y:0};apply();return api;
    },
    getState() {
      return {buttons:{...buttons},dpad:dpad(),sticks:{
        left:{...sticks.left,press:buttons.L3},right:{...sticks.right,press:buttons.R3}
      }};
    },
    subscribe(listener) {
      if(typeof listener!=='function')throw new TypeError('Input listener must be a function');
      subscribers.add(listener);listener(api.getState());
      return ()=>subscribers.delete(listener);
    }
  };
  apply();return Object.freeze(api);
}
