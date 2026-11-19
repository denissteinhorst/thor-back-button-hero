// SPDX-License-Identifier: 0BSD
import * as THREE from 'three';

import { FEATURE_GROUPS, FEATURE_COUNT } from './intro-feature-catalog.js?v=ad-1';

import { HOTKEY_CATEGORIES } from './intro-hotkey-timing.js?v=ad-1';
const smooth=v=>{const x=Math.max(0,Math.min(1,v));return x*x*(3-2*x);};

export function drawHotkeyDemo(top,bottom,logo,frame) {
  const h=frame.hotkeyDemo;if(!(h?.opacity>0))return;
  top.save();top.globalAlpha=h.opacity;top.fillStyle='#111b22';top.fillRect(0,0,1536,864);
  const size=508-148*h.logoMove,cy=432-116*h.logoMove;
  top.save();top.beginPath();top.arc(768,cy,size*.472,0,Math.PI*2);top.clip();top.drawImage(logo,768-size/2,cy-size/2,size,size);top.restore();
  if(h.logoMove>0){top.globalAlpha=h.opacity*h.logoMove;top.fillStyle='#edf5f7';top.textAlign='center';top.textBaseline='middle';top.font='600 79px Arial, sans-serif';top.fillText('Available Functions',768,625,1400);
    if(h.labelsElapsed>0){top.font='500 43px Arial, sans-serif';top.fillStyle='#a7f1ce';top.fillText(`${FEATURE_COUNT} functions · Your shortcuts. Your rules.`,768,731,1400);}}
  top.restore();
  bottom.save();bottom.globalAlpha=h.opacity;bottom.fillStyle='#111b22';bottom.fillRect(0,0,960,836);
  bottom.textBaseline='middle';bottom.textAlign='center';bottom.fillStyle='#f0f5f7';bottom.font='600 64px Arial, sans-serif';bottom.fillText('Possible Hotkeys',480,100);
  const hr=y=>{bottom.strokeStyle='#54707c';bottom.lineWidth=2;bottom.beginPath();bottom.moveTo(90,y);bottom.lineTo(870,y);bottom.stroke();};
  hr(161);
  HOTKEY_CATEGORIES.forEach(({label},i)=>{
    const row=h.inputs.rows[i],opacity=row.reveal;if(opacity===0)return;
    const y=232+i*86+18*(1-opacity);
    bottom.save();bottom.globalAlpha=h.opacity*opacity;
    bottom.fillStyle='#e5edf0';bottom.textAlign='left';bottom.font='500 49px Arial, sans-serif';bottom.fillText(label,110,y,650);
    bottom.textAlign='right';bottom.fillStyle='#a7f1ce';bottom.font='600 58px Arial, sans-serif';bottom.globalAlpha*=.4+.6*row.countReveal;bottom.fillText(String(row.count),845,y+8*(1-row.countReveal));bottom.restore();
  });
  const footer=smooth((h.elapsed-6.55)/.3);
  bottom.globalAlpha=h.opacity*footer;hr(690);bottom.fillStyle='#a7f1ce';bottom.textAlign='center';bottom.font='500 57px Arial, sans-serif';bottom.fillText('Fully customizable',480,759,860);
  bottom.restore();
}

const HERO_FEATURES=[
  ['Swap both apps','Two screens. One shortcut.'],
  ['Bottom-screen app switcher','Switch apps. Keep your game in view.'],
  ['Screenshots','Capture the moment.'],
  ['Record gameplay','With push-to-talk.'],
  ['Macro recorder','Record it once. Replay with a shortcut.'],
  ['Controller focus','Your gamepad. Either screen.'],
  ['Volume & brightness','Quick adjustments. Keep playing.'],
  ['Toggle screens & more','33 functions. Fully customizable.']
];

// Large single-message beats for playback; the final grid rewards pausing.
export function createHotkeyLabels(scene,renderer) {
  const group=new THREE.Group();group.name='intro-hotkey-labels';group.visible=false;scene.add(group);
  function makeCard(entry,index,summary=false){
    const canvas=document.createElement('canvas');canvas.width=720;canvas.height=summary?490:800;
    const c=canvas.getContext('2d'),height=canvas.height;
    function panel(){
      c.clearRect(0,0,720,height);c.fillStyle='#111e29f5';c.beginPath();c.roundRect(2,2,716,height-4,12);c.fill();
      c.strokeStyle='#38596c';c.lineWidth=2;c.stroke();c.fillStyle='#83e8be';c.fillRect(0,0,720,5);
    }
    const map=new THREE.CanvasTexture(canvas);map.colorSpace=THREE.SRGBColorSpace;map.generateMipmaps=false;map.minFilter=THREE.LinearFilter;
    const mesh=new THREE.Mesh(new THREE.PlaneGeometry(1,1),new THREE.MeshBasicMaterial({map,transparent:true,depthTest:false,depthWrite:false,toneMapped:false}));
    mesh.name=summary?'intro-feature-summary':'intro-feature-card';mesh.renderOrder=160;group.add(mesh);
    let previousRows=-1;
    function paint(rows){
      if(rows===previousRows)return;previousRows=rows;panel();c.textBaseline='middle';c.textAlign='left';
      c.fillStyle='#f0f6f8';c.font=`700 ${summary?46:57}px Arial, sans-serif`;c.fillText(entry.title,32,summary?44:66,652);
      c.fillStyle='#a7f1ce';
      c.font=`500 ${summary?32:40}px Arial, sans-serif`;c.fillText(entry.benefit,38,summary?93:144,646);
      {
        c.strokeStyle='#3d5d6c';c.beginPath();c.moveTo(38,summary?122:198);c.lineTo(682,summary?122:198);c.stroke();
        entry.items.forEach(([label],i)=>{
          if(i>=rows)return;
          const y=summary?158+i*49:265+i*76;c.fillStyle='#e2edf1';c.font=`500 ${summary?42:49}px Arial, sans-serif`;c.fillText(label,38,y,644);
        });
        c.fillStyle='#91aab7';c.font='500 34px Arial, sans-serif';if(!summary)c.fillText(`${entry.items.length} functions · Custom options`,38,747,645);
      }
      map.needsUpdate=true;
    }
    paint(summary?entry.items.length:0);renderer?.initTexture(map);
    return {mesh,index,paint,ratio:height/720};
  }
  const summaries=FEATURE_GROUPS.map((entry,i)=>makeCard(entry,i,true));
  function place(card,camera,side,y,opacity,widthFraction){
    card.mesh.visible=opacity>0;if(!card.mesh.visible)return;
    const halfWidth=(camera.right-camera.left)/(2*camera.zoom),width=halfWidth*widthFraction;
    card.mesh.material.opacity=opacity;
    card.mesh.position.set(side*(.735+(1-opacity)*.025),y,0).unproject(camera);
    card.mesh.quaternion.copy(camera.quaternion);card.mesh.scale.set(width,width*card.ratio,1);
  }
  function overlay(width,height,name){
    const canvas=document.createElement('canvas');canvas.width=width;canvas.height=height;
    const map=new THREE.CanvasTexture(canvas);map.colorSpace=THREE.SRGBColorSpace;map.generateMipmaps=false;map.minFilter=THREE.LinearFilter;
    const mesh=new THREE.Mesh(new THREE.PlaneGeometry(1,1),new THREE.MeshBasicMaterial({map,transparent:true,depthTest:false,depthWrite:false,toneMapped:false}));
    mesh.name=name;mesh.renderOrder=165;group.add(mesh);return {canvas,c:canvas.getContext('2d'),map,mesh};
  }
  const hero=overlay(1200,724,'intro-feature-headline'),poster=overlay(1600,210,'intro-poster-title');
  const pc=poster.c;pc.textBaseline='middle';pc.fillStyle='#edf6fa';pc.font='700 86px Arial, sans-serif';pc.fillText('33 functions.',36,81,510);
  pc.fillStyle='#9ee9c7';pc.font='500 35px Arial, sans-serif';pc.fillText('BACK BUTTON HERO 2.0',40,154,505);
  pc.fillStyle='#edf6fa';pc.font='600 43px Arial, sans-serif';pc.fillText('Almost no touch',1080,75,480);pc.fillText('input needed.',1080,128,480);
  poster.map.needsUpdate=true;renderer?.initTexture(poster.map);
  let previousTime=null;
  function screenOverlay(entry,camera,y,opacity,widthFraction){
    const width=(camera.right-camera.left)/camera.zoom*widthFraction;
    entry.mesh.visible=opacity>0;entry.mesh.material.opacity=opacity;
    entry.mesh.position.set(0,y,0).unproject(camera);entry.mesh.quaternion.copy(camera.quaternion);
    entry.mesh.scale.set(width,width*entry.canvas.height/entry.canvas.width,1);
  }
  function update(frame,camera){
    const h=frame.hotkeyDemo;group.visible=!!h&&h.labelsElapsed>0;
    if(!group.visible)return;
    const elapsed=h.highlightTime??-1,summary=h.final??0;
    const index=Math.max(0,Math.min(HERO_FEATURES.length-1,Math.floor(elapsed))),local=elapsed-index;
    const enter=smooth(local/.12),leave=1-smooth((local-.9)/.1);
    screenOverlay(hero,camera,0,(1-summary)*smooth(elapsed/.18),1);
    if(previousTime!==frame.time){
      previousTime=frame.time;const c=hero.c;c.clearRect(0,0,1200,724);c.textAlign='center';c.textBaseline='middle';
      c.fillStyle='#91acb9';c.font='600 23px Arial, sans-serif';c.fillText('BACK BUTTON HERO 2.0',600,32);
      c.fillStyle='#f1f8fc';c.font='600 43px Arial, sans-serif';c.fillText('Almost no touch input needed.',600,84,1120);
      c.save();c.globalAlpha=enter*leave;c.translate(0,12*(1-enter));
      c.fillStyle='#0b1825ed';c.beginPath();c.roundRect(72,535,1056,147,14);c.fill();
      c.fillStyle='#f3f8fc';c.font='700 60px Arial, sans-serif';c.fillText(HERO_FEATURES[index][0],600,586,1010);
      c.fillStyle='#a7f1ce';c.font='500 31px Arial, sans-serif';c.fillText(HERO_FEATURES[index][1],600,643,1000);c.restore();
      for(let i=0;i<8;i++){c.fillStyle=i===index?'#a7f1ce':'#446171';c.fillRect(498+i*27,704,i===index?19:12,3);}
      hero.map.needsUpdate=true;
    }
    screenOverlay(poster,camera,.78,summary,.98);
    for(const card of summaries){
      place(card,camera,(card.index%3-1)*(.65/.735),.2-Math.floor(card.index/3)*.71,summary,.61);
    }
  }

  return {update,hide(){group.visible=false;}};
}
