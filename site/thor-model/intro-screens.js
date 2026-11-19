// SPDX-License-Identifier: 0BSD
import * as THREE from 'three';
import { roundedOutline } from './geometry.js';
import { sampleAtmosphere, drawBirds } from './intro-atmosphere.js';
import { drawHotkeyDemo } from './intro-hotkey-demo.js?v=ad-1';
import { drawPauseDemo } from './intro-pause-demo.js?v=ad-1';
import { drawHomeOption } from './intro-home-option.js?v=ad-1';

function loadImage(url) {
  return new Promise((resolve,reject)=>{
    const image=new Image();image.onload=async()=>{
      try {await image.decode();resolve(image);}catch(error){reject(error);}
    };
    image.onerror=()=>reject(new Error('Could not load intro asset: '+url));image.src=url;
  });
}
function canvas(width,height) {
  const element=document.createElement('canvas');element.width=width;element.height=height;
  return element;
}
function texture(element) {
  const map=new THREE.CanvasTexture(element);map.colorSpace=THREE.SRGBColorSpace;map.anisotropy=4;
  // Animated LCD content is uploaded repeatedly; keep sampling stable instead
  // of rebuilding a mip chain for every tiny background movement.
  map.generateMipmaps=false;map.minFilter=THREE.LinearFilter;
  return map;
}
function screen(parent,width,height,radius,depth,map) {
  const geometry=new THREE.ShapeGeometry(roundedOutline(width,height,radius));
  const positions=geometry.getAttribute('position'),uv=geometry.getAttribute('uv');
  for(let i=0;i<uv.count;i++)uv.setXY(i,positions.getX(i)/width+.5,positions.getY(i)/height+.5);
  // Fade emitted colour, not a nearly coplanar transparent surface over the OLED.
  // A depth bias prevents glass/content competition while retaining lid occlusion.
  const material=new THREE.MeshBasicMaterial({map,color:0x000000,toneMapped:false,
    polygonOffset:true,polygonOffsetFactor:-1,polygonOffsetUnits:-2});
  const mesh=new THREE.Mesh(geometry,material);mesh.position.z=depth;mesh.visible=false;
  mesh.name='intro-screen-content';parent.add(mesh);return mesh;
}

export async function createIntroScreens(thor,renderer) {
  const [nature,logo]=await Promise.all([
    loadImage(new URL('./assets/intro-nature.png',import.meta.url).href),
    loadImage(new URL('../assets/icon.png',import.meta.url).href)
  ]);
  const upper=canvas(1536,864),lower=canvas(960,836);
  const upperBase=canvas(1536,864),lowerBase=canvas(960,836);
  const split=nature.height*.47;
  upperBase.getContext('2d').drawImage(nature,0,0,nature.width,split,0,0,1536,864);
  // The lower panel is physically narrower: continue through the centre of the
  // same world instead of squeezing the entire panorama into that screen.
  const lowerWidth=nature.width*75.15/132.83;
  lowerBase.getContext('2d').drawImage(nature,(nature.width-lowerWidth)/2,split,lowerWidth,nature.height-split,0,0,960,836);
  upper.getContext('2d').drawImage(upperBase,0,0);lower.getContext('2d').drawImage(lowerBase,0,0);
  const lavaGlow=canvas(960,836),lavaContext=lavaGlow.getContext('2d');
  const lava=lowerBase.getContext('2d').getImageData(0,0,960,836);
  for(let i=0;i<lava.data.length;i+=4){
    const [r,g,b]=[lava.data[i],lava.data[i+1],lava.data[i+2]];
    const warmth=Math.max(0,Math.min(1,(r-g*1.4-b*.3)/120));
    lava.data[i]=255;lava.data[i+1]=110;lava.data[i+2]=12;lava.data[i+3]=Math.round(warmth*100);
  }
  lavaContext.putImageData(lava,0,0);
  const upperMap=texture(upper),lowerMap=texture(lower);
  const upperMesh=screen(thor.root.getObjectByName('upper-active-display'),132.7,74.6,.7,.08,upperMap);
  const lowerMesh=screen(thor.root.getObjectByName('lower-active-display'),75.02,65.32,.5,.12,lowerMap);
  const watermarkCanvas=canvas(512,512);watermarkCanvas.getContext('2d').drawImage(logo,0,0,512,512);
  const watermarkMap=texture(watermarkCanvas);
  const watermark=new THREE.Mesh(new THREE.PlaneGeometry(132.83*.28,132.83*.28),new THREE.ShaderMaterial({
    uniforms:{logo:{value:watermarkMap},tint:{value:new THREE.Color('#82b6c4')},opacity:{value:.2}},
    vertexShader:'varying vec2 logoUv;void main(){logoUv=uv;gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0);}',
    fragmentShader:`uniform sampler2D logo;uniform vec3 tint;uniform float opacity;varying vec2 logoUv;
      void main(){vec4 original=texture2D(logo,logoUv);
        float circle=1.0-smoothstep(0.462,0.467,length(logoUv-vec2(0.5)));
        float luminance=dot(original.rgb,vec3(0.2126,0.7152,0.0722));
        gl_FragColor=vec4(tint*(0.08+0.92*luminance),original.a*circle*opacity);
        #include <colorspace_fragment>
      }`,
    transparent:true,depthWrite:false,toneMapped:false,
    polygonOffset:true,polygonOffsetFactor:-1,polygonOffsetUnits:-2
  }));
  watermark.name='intro-wire-watermark';watermark.position.z=.09;watermark.visible=false;
  thor.root.getObjectByName('upper-active-display').add(watermark);
  const titleCanvas=canvas(1536,448),c=titleCanvas.getContext('2d');
  c.textAlign='center';c.textBaseline='middle';c.fillStyle='#050607';
  c.font='500 112px Arial, sans-serif';c.fillText('Back Button Hero 2.0',768,153);
  c.font='400 54px Arial, sans-serif';c.fillText('For AYN Thor *all models',768,285);
  const title=new THREE.Mesh(new THREE.PlaneGeometry(120,35),new THREE.MeshBasicMaterial({map:texture(titleCanvas),transparent:true,depthWrite:false,toneMapped:false}));
  // The label is on the outer lid face, opposite the upper OLED. Single-sided
  // geometry and normal depth testing let the actual lid hide it as it opens.
  title.name='intro-title';title.position.set(0,43.4,-4.34);title.rotation.x=Math.PI;title.visible=false;
  thor.hinge.add(title);
  for(const map of [upperMap,lowerMap,title.material.map,watermarkMap])renderer?.initTexture(map);
  let previousBrand=-1,previousTime=-1;
  function drawBackground(context,base,atmosphere) {
    const {width,height}=base,zoom=atmosphere.zoom;
    context.drawImage(base,width*(1-zoom)/2+atmosphere.x*width,height*(1-zoom)/2+atmosphere.y*height,width*zoom,height*zoom);
  }
  function update(state,enabled) {
    watermark.visible=false;
    title.visible=enabled;upperMesh.visible=lowerMesh.visible=enabled&&state.screenOpacity>0;
    if(!enabled)return;
    upperMesh.material.color.setScalar(state.screenOpacity);lowerMesh.material.color.setScalar(state.screenOpacity);
    title.material.opacity=state.titleOpacity;
    if(state.brandOpacity===previousBrand&&state.time===previousTime)return;
    previousBrand=state.brandOpacity;previousTime=state.time;
    const top=upper.getContext('2d'),bottom=lower.getContext('2d');
    const atmosphere=sampleAtmosphere(state.time);
    drawBackground(top,upperBase,atmosphere);drawBackground(bottom,lowerBase,atmosphere);
    bottom.save();bottom.globalCompositeOperation='screen';bottom.globalAlpha=atmosphere.lava;
    drawBackground(bottom,lavaGlow,atmosphere);bottom.restore();
    for(const [ctx,width,height] of [[top,1536,864],[bottom,960,836]]){
      ctx.fillStyle=`rgba(3,9,14,${state.brandOpacity*.32})`;ctx.fillRect(0,0,width,height);
    }
    top.save();top.beginPath();top.rect(0,0,1536,864);top.moveTo(977,432);top.arc(768,432,209,0,Math.PI*2);top.clip('evenodd');
    drawBirds(top,state.brandElapsed);top.restore();
    top.save();top.globalAlpha=state.brandOpacity;
    top.shadowColor='#0009';top.shadowBlur=35;
    // Clip the existing square icon to its circular silhouette; no logo redesign.
    top.beginPath();top.arc(768,432,202,0,Math.PI*2);top.clip();top.drawImage(logo,555,219,426,426);top.restore();
    bottom.save();bottom.globalAlpha=state.brandOpacity;bottom.textAlign='center';bottom.textBaseline='middle';
    bottom.fillStyle='#fff';bottom.shadowColor='#000';bottom.shadowBlur=16;
    bottom.font='600 76px Arial, sans-serif';bottom.fillText('Back Button Hero',480,373);
    bottom.font='400 38px Arial, sans-serif';bottom.fillText('Version 2.0 - Feature preview',480,463);bottom.restore();
    drawHomeOption(top,state);
    drawPauseDemo(top,bottom,logo,state);
    drawHotkeyDemo(top,bottom,logo,state);
    upperMap.needsUpdate=lowerMap.needsUpdate=true;
  }
  return {update,setWireWatermarkVisible(value){watermark.visible=value;}};
}
