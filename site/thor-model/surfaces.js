// SPDX-License-Identifier: 0BSD
import * as THREE from 'three';

const textureCache=new Map();
export function createMarkTexture(mark, color) {
  const key=mark+':'+color;
  if(textureCache.has(key))return textureCache.get(key);
  const canvas=document.createElement('canvas');canvas.width=512;canvas.height=256;
  const c=canvas.getContext('2d');c.strokeStyle=color;c.fillStyle=color;c.lineWidth=11;
  c.lineCap='round';c.lineJoin='round';c.translate(256,128);
  const line=points=>{c.beginPath();points.forEach(([x,y],i)=>i?c.lineTo(x,y):c.moveTo(x,y));c.stroke();};
  if(mark==='home') {
    line([[-52,2],[0,-45],[52,2]]);line([[-35,-10],[-35,44],[-10,44],[-10,12],[12,12],[12,44],[35,44],[35,-10]]);
  }else if(mark==='back') {
    line([[-38,-22],[-62,0],[-38,22]]);c.beginPath();c.moveTo(-60,0);c.lineTo(12,0);c.quadraticCurveTo(53,0,53,40);c.stroke();
  }else if(mark==='select') {
    c.strokeRect(-32,-40,64,80);line([[-11,-37],[-11,37]]);
  }else if(mark==='start') {
    line([[-27,-42],[40,0],[-27,42],[-27,-42]]);
  }else if(mark==='power') {
    c.beginPath();c.arc(0,3,45,-1,Math.PI+1);c.stroke();line([[0,-60],[0,-4]]);
  }else if(mark==='direction') {
    line([[-28,22],[0,-23],[28,22],[-28,22]]);line([[-13,14],[0,-8],[13,14]]);
  }else if(mark==='+') {
    line([[-35,0],[35,0]]);line([[0,-35],[0,35]]);
  }else if(mark==='−') {
    line([[-35,0],[35,0]]);
  }else {
    c.font=(mark.length===1?'500 148px':'400 96px')+' Arial, sans-serif';
    c.textAlign='center';c.textBaseline='middle';c.fillText(mark,0,7);
  }
  // Crop alpha margins so the plane dimensions are the actual visible glyph size.
  // The previous fixed 512×256 atlas shrank legends by different factors in x/y.
  const rgba=c.getImageData(0,0,512,256).data;
  let left=512,top=256,right=0,bottom=0;
  for(let y=0;y<256;y++)for(let x=0;x<512;x++)if(rgba[(y*512+x)*4+3]){
    left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);
  }
  const cropped=document.createElement('canvas');cropped.width=right-left+7;cropped.height=bottom-top+7;
  cropped.getContext('2d').drawImage(canvas,left,top,right-left+1,bottom-top+1,3,3,right-left+1,bottom-top+1);
  const tex=new THREE.CanvasTexture(cropped);tex.colorSpace=THREE.SRGBColorSpace;
  tex.anisotropy=4;textureCache.set(key,tex);return tex;
}
