// SPDX-License-Identifier: 0BSD
import * as THREE from 'three';

// Scissored passes keep the established material, colour and antialiasing paths.
// Only a narrow additive highlight is rendered again along the moving boundary.
export function createIntroWireframe(renderer,scene,camera,thor,design,floor,decorate=()=>{}) {
  const size=new THREE.Vector2(),previousScissor=new THREE.Vector4();
  const bounds=new THREE.Box3(),point=new THREE.Vector3();
  const content=[];thor.root.traverse(o=>{if(o.name==='intro-screen-content'||o.name==='intro-title')content.push(o);});
  const watermarks=[];thor.root.traverse(o=>{if(o.name==='intro-wire-watermark')watermarks.push(o);});
  const shine=new THREE.ShaderMaterial({
    uniforms:{scanX:{value:0},pixelWidth:{value:1}},
    vertexShader:`void main(){vec4 point=vec4(position,1.0);
      #ifdef USE_INSTANCING
      point=instanceMatrix*point;
      #endif
      gl_Position=projectionMatrix*modelViewMatrix*point;}`,
    fragmentShader:`uniform float scanX;uniform float pixelWidth;
      void main(){float d=(gl_FragCoord.x-scanX)/max(pixelWidth,1.0);
        float glow=exp(-d*d*8.0);if(glow<0.003)discard;
        gl_FragColor=vec4(vec3(0.65,0.93,1.0),glow*0.85);
        #include <colorspace_fragment>
      }`,
    transparent:true,blending:THREE.AdditiveBlending,depthWrite:false,toneMapped:false,
    polygonOffset:true,polygonOffsetFactor:-1,polygonOffsetUnits:-2,side:THREE.DoubleSide
  });
  const savedDesign=design.getState();
  // Populate contour caches while the intro is still loading, before playback.
  design.setSolidControls(false).setEnabled(true).setEnabled(false).setSolidControls(savedDesign.solidControls);
  function normal() {design.setSolidControl(null).setSolidControlNames([]).setEnabled(false).setSolidControls(true);watermarks.forEach(o=>o.visible=false);}
  function wire(frame) {design.setSolidControls(false).setSolidControl((frame.holdReveal??frame.backReveal)>0?(frame.holdKey??'back'):null).setSolidControlNames(frame.revealedControls??[]).setEnabled(true);watermarks.forEach(o=>o.visible=true);decorate();}
  function render(frame,view=camera) {
    if(frame.wireProgress===0){normal();renderer.render(scene,view);return;}
    const visible=content.map(o=>o.visible),floorVisible=floor.visible;
    const watermarkVisible=watermarks.map(o=>o.visible);
    const autoClear=renderer.autoClear,override=scene.overrideMaterial,shadowUpdate=renderer.shadowMap.autoUpdate;
    const scissorTest=renderer.getScissorTest();renderer.getScissor(previousScissor);renderer.getSize(size);
    const hideContent=()=>{content.forEach(o=>o.visible=false);floor.visible=false;};
    const restoreContent=()=>{content.forEach((o,i)=>o.visible=visible[i]);floor.visible=floorVisible;};
    try {
      if(frame.wireProgress===1){wire(frame);hideContent();renderer.shadowMap.autoUpdate=false;renderer.render(scene,view);return;}
      normal();bounds.setFromObject(thor.root);
      let left=1,right=0;
      for(const x of [bounds.min.x,bounds.max.x])for(const y of [bounds.min.y,bounds.max.y])for(const z of [bounds.min.z,bounds.max.z]){
        point.set(x,y,z).project(view);const px=(point.x+1)/2;left=Math.min(left,px);right=Math.max(right,px);
      }
      const sweep=left-.025+(right-left+.05)*frame.wireProgress;
      const split=Math.max(0,Math.min(size.x,Math.round(sweep*size.x)));
      renderer.autoClear=false;renderer.setScissorTest(false);renderer.clear();renderer.setScissorTest(true);
      renderer.setScissor(split,0,size.x-split,size.y);renderer.render(scene,view);
      renderer.shadowMap.autoUpdate=false;
      wire(frame);hideContent();renderer.setScissor(0,0,split,size.y);renderer.render(scene,view);
      renderer.setScissorTest(false);
      shine.uniforms.scanX.value=sweep*size.x*renderer.getPixelRatio();
      shine.uniforms.pixelWidth.value=size.x*.022*renderer.getPixelRatio();
      watermarks.forEach(o=>o.visible=false);
      scene.overrideMaterial=shine;renderer.render(scene,view);
    }finally{
      restoreContent();scene.overrideMaterial=override;renderer.autoClear=autoClear;
      watermarks.forEach((o,i)=>o.visible=watermarkVisible[i]);
      renderer.shadowMap.autoUpdate=shadowUpdate;
      renderer.setScissor(previousScissor);renderer.setScissorTest(scissorTest);
    }
  }
  return {render,reset(){normal();},dispose(){normal();shine.dispose();}};
}
