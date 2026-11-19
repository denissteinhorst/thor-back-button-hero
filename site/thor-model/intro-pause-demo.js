// SPDX-License-Identifier: 0BSD
import * as THREE from 'three';

import { PAUSE_COMBOS } from './intro-pause-timing.js?v=ad-1';
const smooth=v=>{const x=Math.max(0,Math.min(1,v));return x*x*(3-2*x);};

export function drawPauseDemo(top,bottom,logo,state) {
  const p=state.pauseDemo;if(!(p?.opacity>0))return;
  top.save();top.globalAlpha=p.opacity;top.fillStyle='#111b22';top.fillRect(0,0,1536,864);
  top.save();top.beginPath();top.arc(768,432,240,0,Math.PI*2);top.clip();
  top.filter=p.pressure>0?'grayscale(1)':'none';top.drawImage(logo,514,178,508,508);top.restore();
  // Pause bars sit over the logo only while the physical pair is depressed.
  if(p.pressure>0){
    top.globalAlpha=p.opacity*p.pressure;top.fillStyle='#0a1019cc';
    top.beginPath();top.roundRect(648,312,240,240,48);top.fill();top.fillStyle='#ffffff';
    top.fillRect(707,365,40,134);top.fillRect(789,365,40,134);
  }
  top.restore();
  bottom.save();bottom.globalAlpha=p.opacity;bottom.fillStyle='#111b22';bottom.fillRect(0,0,960,836);
  bottom.textAlign='center';bottom.textBaseline='middle';bottom.fillStyle='#f0f5f7';
  bottom.font='600 58px Arial, sans-serif';bottom.fillText('Pause Back Button Hero',480,100,860);
  const hr=y=>{bottom.strokeStyle='#54707c';bottom.lineWidth=2;bottom.beginPath();bottom.moveTo(90,y);bottom.lineTo(870,y);bottom.stroke();};
  hr(161);
  PAUSE_COMBOS.forEach((combo,i)=>{
    if(i>=p.rows)return;
    const reveal=smooth((p.elapsed-.2-i*.8)/.28),y=224+i*91+18*(1-reveal);
    bottom.save();bottom.globalAlpha=p.opacity*reveal;
    if(i===p.index&&p.pressure>0){bottom.fillStyle=`rgba(103,213,171,${.16*p.pressure})`;bottom.beginPath();bottom.roundRect(140,y-36,680,72,18);bottom.fill();}
    bottom.fillStyle=i===p.index&&p.pressure>0?'#a7f1ce':'#e5edf0';
    bottom.font='600 56px Arial, sans-serif';bottom.fillText(combo.label,480,y);bottom.restore();
  });
  if(p.choose>0){bottom.globalAlpha=p.opacity*p.choose;hr(690);bottom.fillStyle='#a7f1ce';bottom.font='500 58px Arial, sans-serif';bottom.fillText('Choose yours',480,759);}
  bottom.restore();
}

export function createPauseHighlights(thor) {
  const entries=[],wanted=new Set(['L1','R1','L2','R2','leftStick','rightStick','start','select','home','back','dpad','A','B','X','Y']);
  // Collect originals before adding overlays, excluding earlier intro helpers.
  thor.root.traverse(object=>{
    if(!object.isMesh||object.name.startsWith('mark-')||Array.isArray(object.material))return;
    let control=null;
    for(let parent=object;parent;parent=parent.parent){
      if(parent.name.startsWith('intro-')||parent.name==='design-wire-overlay')return;
      control??=parent.userData.inputControl;
    }
    if(wanted.has(control))entries.push({source:object,control});
  });
  for(const entry of entries){
    const material=new THREE.MeshBasicMaterial({color:'#83e8be',transparent:true,opacity:0,
      depthTest:!(/^[LR][12]$/.test(entry.control)),depthWrite:false,toneMapped:false,
      polygonOffset:true,polygonOffsetFactor:-3,polygonOffsetUnits:-4});
    const mesh=new THREE.Mesh(entry.source.geometry,material);mesh.name='intro-pause-highlight';mesh.renderOrder=140;mesh.visible=false;
    mesh.position.copy(entry.source.position);mesh.quaternion.copy(entry.source.quaternion);mesh.scale.copy(entry.source.scale);
    entry.source.parent.add(mesh);entry.mesh=mesh;
  }
  function hide(){entries.forEach(({mesh})=>mesh.visible=false);}
  function update(frame){
    const strengths=new Map((frame.controlCues??[]).map(cue=>[cue.key,cue.strength]));
    for(const {source,mesh,control} of entries){
      const strength=strengths.get(control)??0;
      mesh.visible=strength>0;
      if(!mesh.visible)continue;
      mesh.position.copy(source.position);mesh.quaternion.copy(source.quaternion);mesh.scale.copy(source.scale);
      mesh.material.opacity=.8*strength;
    }
  }
  return {update,hide};
}
