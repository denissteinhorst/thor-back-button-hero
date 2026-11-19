// SPDX-License-Identifier: 0BSD
import * as THREE from 'three';

const green=new THREE.Color('#67d58a'),yellow=new THREE.Color('#f2ce58'),red=new THREE.Color('#f05c57');
function colourAt(p){return p<.55?green.clone().lerp(yellow,p/.55):yellow.clone().lerp(red,(p-.55)/.45);}

// A restrained reach scale, anchored to the composition rather than wrapped
// around the product. Reversing its direction preserves the same reading in
// both camera views without covering the device or its external callout.
export function createReachGauge(scene,renderer) {
  const group=new THREE.Group();group.name='intro-reach-gauge';group.visible=false;scene.add(group);
  const uniforms={progress:{value:0},opacity:{value:1},green:{value:green},yellow:{value:yellow},red:{value:red}};
  const material=new THREE.ShaderMaterial({uniforms,
    vertexShader:'attribute float reach;varying float arcReach;void main(){arcReach=reach;gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0);}',
    fragmentShader:`uniform float progress;uniform float opacity;uniform vec3 green;uniform vec3 yellow;uniform vec3 red;varying float arcReach;
      void main(){vec3 colour=arcReach<0.55?mix(green,yellow,arcReach/0.55):mix(yellow,red,(arcReach-0.55)/0.45);
        float visited=1.0-smoothstep(progress-0.012,progress+0.012,arcReach);
        gl_FragColor=vec4(colour,opacity*mix(0.18,0.85,visited));
        #include <colorspace_fragment>
      }`,transparent:true,depthTest:false,depthWrite:false,toneMapped:false,side:THREE.DoubleSide
  });
  const rail=new THREE.Mesh(new THREE.BufferGeometry(),material);rail.renderOrder=150;group.add(rail);
  const marker=new THREE.Mesh(new THREE.CircleGeometry(1.1,24),new THREE.MeshBasicMaterial({color:green,transparent:true,depthTest:false,depthWrite:false,toneMapped:false}));
  marker.renderOrder=153;group.add(marker);
  function label(text,colour,width,font=500) {
    const canvas=document.createElement('canvas');canvas.width=768;canvas.height=128;
    const c=canvas.getContext('2d');c.font=`${font} 68px Arial, sans-serif`;c.textAlign='center';c.textBaseline='middle';
    c.fillStyle=colour;c.fillText(text,384,66,740);
    const map=new THREE.CanvasTexture(canvas);map.colorSpace=THREE.SRGBColorSpace;renderer?.initTexture(map);
    const mesh=new THREE.Mesh(new THREE.PlaneGeometry(width,width/6),new THREE.MeshBasicMaterial({map,transparent:true,depthTest:false,depthWrite:false,toneMapped:false}));
    mesh.name='intro-reach-label';mesh.renderOrder=154;group.add(mesh);return mesh;
  }
  const easy=label('Easy to reach','#9bdfb1',39),hard=label('Hard to reach','#eea09a',39);
  const title=label('HOTKEY REACH','#b2c6ce',38,600);
  let direction=1,width=0;
  function point(p){return new THREE.Vector3((p-.5)*width*direction,Math.sin(p*Math.PI)*3,.08);}
  function configure(root,camera,origin,first,last,buttonRadius) {
    group.quaternion.copy(camera.quaternion);
    const view=origin.clone().applyMatrix4(camera.matrixWorldInverse);
    const halfHeight=-view.z*Math.tan(THREE.MathUtils.degToRad(camera.fov/2))/camera.zoom;
    // Keep the slim scale in a fixed bottom margin. World-space size scales
    // with the viewport, so neither mirrored view can clip its end labels.
    const centre=new THREE.Vector3(0,-halfHeight+16,view.z).applyMatrix4(camera.matrixWorld);
    group.position.copy(centre);width=halfHeight*camera.aspect*.94;direction=last<first?1:-1;
    const positions=[],reaches=[],indices=[];
    for(let i=0;i<=96;i++){
      const p=i/96,v=point(p);
      for(const dy of [-.55,.55]){positions.push(v.x,v.y+dy,.06);reaches.push(p);}
      if(i<96){const n=i*2;indices.push(n,n+1,n+2,n+1,n+3,n+2);}
    }
    rail.geometry.dispose();rail.geometry=new THREE.BufferGeometry();
    rail.geometry.setAttribute('position',new THREE.Float32BufferAttribute(positions,3));
    rail.geometry.setAttribute('reach',new THREE.Float32BufferAttribute(reaches,1));rail.geometry.setIndex(indices);
    easy.position.copy(point(0)).add(new THREE.Vector3(0,-6,0));
    hard.position.copy(point(1)).add(new THREE.Vector3(0,-6,0));title.position.set(0,10,.08);
    return buttonRadius+8;
  }
  function update(frame) {
    group.visible=frame.radialStarted;if(!group.visible)return;
    const opacity=1-Math.max(0,1-frame.radialColour)*.6;
    uniforms.progress.value=frame.radialProgress;uniforms.opacity.value=opacity;
    marker.position.copy(point(frame.radialProgress));marker.material.color.copy(colourAt(frame.radialProgress));
    marker.material.opacity=opacity;
    for(const label of [easy,hard,title])label.material.opacity=opacity;
  }
  return {configure,update,hide(){group.visible=false;}};
}
