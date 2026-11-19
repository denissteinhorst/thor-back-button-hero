// SPDX-License-Identifier: 0BSD
import * as THREE from 'three';
import { placeDeviceCallout } from './intro-callout.js?v=port-anchor-1';

export function createBackHoldHint(thor,scene,renderer,{key='back',text='Hold Back',subtitle=''}={}) {
  const joint=thor.root.getObjectByName(key+'-joint');
  const group=new THREE.Group();group.name='intro-'+key+'-hint';group.visible=false;joint.add(group);
  const material=new THREE.MeshBasicMaterial({color:'#ffda82',transparent:true,opacity:0,depthWrite:false,toneMapped:false,side:THREE.DoubleSide});
  const ring=new THREE.Mesh(new THREE.RingGeometry(2.8,3.35,64),material);
  ring.rotation.x=-Math.PI/2;ring.position.y=.65;group.add(ring);
  const ripple=new THREE.Mesh(new THREE.RingGeometry(4.4,4.65,64),material.clone());
  ripple.rotation.x=-Math.PI/2;ripple.position.y=.65;group.add(ripple);
  const canvas=document.createElement('canvas');canvas.width=512;canvas.height=subtitle?224:144;
  const c=canvas.getContext('2d');c.fillStyle='#101f27';c.fillRect(0,0,512,canvas.height);
  c.strokeStyle='#ffda82';c.lineWidth=5;c.strokeRect(3,3,506,canvas.height-6);
  c.fillStyle='#ffda82';c.textAlign='center';c.textBaseline='middle';c.font='600 78px Arial, sans-serif';c.fillText(text,256,76);
  if(subtitle){c.font='400 43px Arial, sans-serif';c.fillText(subtitle,256,162);}
  const map=new THREE.CanvasTexture(canvas);map.colorSpace=THREE.SRGBColorSpace;
  const caption=new THREE.Sprite(new THREE.SpriteMaterial({map,transparent:true,depthTest:false,depthWrite:false,toneMapped:false}));
  const width=subtitle?44:38;
  caption.name=key==='back'?'intro-hold-caption':'intro-home-caption';caption.scale.set(width,width*canvas.height/512,1);caption.visible=false;caption.renderOrder=1000;scene.add(caption);
  const leader=new THREE.Line(new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(),new THREE.Vector3()]),new THREE.LineBasicMaterial({color:'#ffda82',transparent:true,depthTest:false,depthWrite:false,toneMapped:false}));
  leader.visible=false;leader.renderOrder=999;scene.add(leader);renderer?.initTexture(map);
  const origin=new THREE.Vector3(),right=new THREE.Vector3(),up=new THREE.Vector3();
  function update(frame,camera) {
    const amount=frame?.[key==='home'?'homeReveal':'backReveal']??0;group.visible=caption.visible=leader.visible=amount>0;
    if(amount===0)return;
    const phase=frame.holdProgress*Math.PI*4;
    const pulse=.7+.3*Math.cos(phase);
    material.opacity=amount*pulse;ring.scale.setScalar(1+.08*Math.sin(phase));
    const cycle=(frame.holdProgress*2)%1;
    ripple.scale.setScalar(1+cycle*.65);ripple.material.opacity=amount*(1-cycle)*.65;
    caption.material.opacity=leader.material.opacity=amount;
    joint.getWorldPosition(origin);origin.y+=.7;
    up.set(0,1,0).applyQuaternion(camera.quaternion);
    right.set(1,0,0).applyQuaternion(camera.quaternion);
    placeDeviceCallout(thor.root,thor.root.getObjectByName('microsd-cover'),camera,caption);
    const positions=leader.geometry.attributes.position;
    positions.setXYZ(0,origin.x,origin.y,origin.z);
    const end=caption.position.clone().addScaledVector(up,caption.scale.y/2).addScaledVector(right,caption.scale.x/2);
    positions.setXYZ(1,end.x,end.y,end.z);positions.needsUpdate=true;leader.geometry.computeBoundingSphere();
  }
  return {update,hide(){group.visible=caption.visible=leader.visible=false;}};
}
