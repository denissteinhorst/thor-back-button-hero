// SPDX-License-Identifier: 0BSD
import * as THREE from 'three';

// Display-only vertex clustering keeps the very dense reconstruction readable.
// The original geometry is retained and restored when leaving the design view.
export function designGeometry(source) {
  source.computeBoundingBox();
  const size=source.boundingBox.getSize(new THREE.Vector3()),dimensions=size.toArray();
  // Collapsing the two sides of a thin perforated panel can leave artificial
  // boundary edges between its apertures. Its exact, planar caps are cheap.
  if(Math.max(...dimensions)>25&&Math.min(...dimensions)/Math.max(...dimensions)<.03)return source.clone();
  const step=THREE.MathUtils.clamp(size.length()/34,.05,2.2);
  const position=source.attributes.position,clusters=new Map(),vertices=[],mapping=[];
  for(let i=0;i<position.count;i++) {
    const x=position.getX(i),y=position.getY(i),z=position.getZ(i);
    const key=[x,y,z].map(v=>Math.round(v/step)).join(',');
    let cluster=clusters.get(key);
    if(!cluster){cluster={id:vertices.length,sum:new THREE.Vector3(),count:0};clusters.set(key,cluster);vertices.push(cluster);}
    cluster.sum.add(new THREE.Vector3(x,y,z));cluster.count++;mapping[i]=cluster.id;
  }
  const positions=vertices.flatMap(v=>v.sum.divideScalar(v.count).toArray()),indices=[],seen=new Set();
  const index=source.index?.array,count=index?.length??position.count;
  for(let i=0;i<count;i+=3) {
    const triangle=[0,1,2].map(j=>mapping[index?index[i+j]:i+j]);
    if(new Set(triangle).size<3)continue;
    const key=[...triangle].sort((a,b)=>a-b).join(',');if(seen.has(key))continue;
    seen.add(key);indices.push(...triangle);
  }
  const result=new THREE.BufferGeometry();result.setAttribute('position',new THREE.Float32BufferAttribute(positions,3));result.setIndex(indices);
  result.computeVertexNormals();return result;
}

// Expand each contour segment to an antialiased screen-space ribbon. Native
// WebGL line widths are not portable; these original shaders keep case outlines
// legible at different zoom levels and pixel densities without a new dependency.
function contourGeometry(edges) {
  const p=edges.attributes.position,position=[],start=[],end=[],side=[],indices=[];
  for(let i=0;i<p.count;i+=2) {
    const a=new THREE.Vector3().fromBufferAttribute(p,i).toArray(),b=new THREE.Vector3().fromBufferAttribute(p,i+1).toArray(),offset=position.length/3;
    position.push(...a,...a,...b,...b);
    for(let j=0;j<4;j++){start.push(...a);end.push(...b);side.push(j%2?1:-1);}
    indices.push(offset,offset+1,offset+2,offset+2,offset+1,offset+3);
  }
  const g=new THREE.BufferGeometry();g.setAttribute('position',new THREE.Float32BufferAttribute(position,3));
  g.setAttribute('segmentStart',new THREE.Float32BufferAttribute(start,3));g.setAttribute('segmentEnd',new THREE.Float32BufferAttribute(end,3));
  g.setAttribute('lineSide',new THREE.Float32BufferAttribute(side,1));g.setIndex(indices);return g;
}

export function createDesignMode(root) {
  const parts=[],cache=new Map(),owned=new Set();
  const surface=new THREE.MeshBasicMaterial({color:'#101f27',side:THREE.DoubleSide,polygonOffset:true,polygonOffsetFactor:.04,polygonOffsetUnits:1,toneMapped:false});
  const lines=new THREE.ShaderMaterial({
    uniforms:{resolution:{value:new THREE.Vector2(1024,768)},lineWidth:{value:1.8},color:{value:new THREE.Color('#82b6c4')}},
    vertexShader:`
      uniform vec2 resolution; uniform float lineWidth;
      attribute vec3 segmentStart; attribute vec3 segmentEnd; attribute float lineSide;
      varying float edgeSide;
      void main() {
        vec4 a=projectionMatrix*modelViewMatrix*vec4(segmentStart,1.0);
        vec4 b=projectionMatrix*modelViewMatrix*vec4(segmentEnd,1.0);
        vec2 delta=(b.xy/b.w-a.xy/a.w)*resolution;
        vec2 normal=vec2(-delta.y,delta.x)/max(length(delta),0.0001);
        vec4 point=projectionMatrix*modelViewMatrix*vec4(position,1.0);
        point.xy+=normal*lineWidth/resolution*lineSide*point.w;
        gl_Position=point; edgeSide=lineSide;
      }`,
    fragmentShader:`
      uniform vec3 color; varying float edgeSide;
      void main() {
        float coverage=1.0-smoothstep(0.55,1.0,abs(edgeSide));
        gl_FragColor=vec4(color,0.85*coverage);
        #include <colorspace_fragment>
      }`,
    side:THREE.DoubleSide,transparent:true,depthWrite:false,toneMapped:false
  });
  const instances=new THREE.MeshBasicMaterial({color:'#65becd',wireframe:true,toneMapped:false});
  const state={enabled:false,solidControls:true};let disposed=false;
  root.traverse(object=>{
    if(!object.isMesh)return;
    let control=false;
    for(let parent=object;parent;parent=parent.parent)if(parent.userData.inputControl){control=true;break;}
    parts.push({object,control,legend:object.name.startsWith('mark-'),geometry:object.geometry,material:object.material,
      visible:object.visible,castShadow:object.castShadow,receiveShadow:object.receiveShadow,overlay:null});
  });
  function bundle(geometry) {
    if(cache.has(geometry))return cache.get(geometry);
    const display=designGeometry(geometry),segments=new THREE.EdgesGeometry(display,25),edges=contourGeometry(segments);segments.dispose();
    for(const g of [display,edges])owned.add(g);
    const result={display,edges};cache.set(geometry,result);return result;
  }
  function overlay(part) {
    if(part.overlay)return part.overlay;
    const data=bundle(part.geometry),group=new THREE.Group();group.name='design-wire-overlay';
    // Coplanar diagonals and shallow tessellation edges disappear. Broad panels
    // stay quiet so the original, coloured controls remain the visual focus.
    group.add(new THREE.Mesh(data.edges,lines));
    // Overlay meshes must not intercept future device picking.
    group.traverse(o=>{o.raycast=()=>{};});part.object.add(group);part.overlay=group;return group;
  }
  function apply() {
    for(const part of parts) {
      const o=part.object,wire=state.enabled&&!(state.solidControls&&part.control);
      if(part.overlay)part.overlay.visible=false;
      o.geometry=part.geometry;o.material=part.material;o.visible=part.visible;
      o.castShadow=part.castShadow;o.receiveShadow=part.receiveShadow;
      if(!wire)continue;
      if(part.legend){o.visible=false;continue;}
      o.castShadow=false;o.receiveShadow=false;
      if(o.isInstancedMesh){o.material=instances;continue;}
      const data=bundle(part.geometry);
      o.geometry=data.display;o.material=surface;
      overlay(part).visible=true;
    }
  }
  const set=(key,value)=>{
    if(disposed)throw new Error('Design mode has been disposed');
    if(typeof value!=='boolean')throw new TypeError('Design settings must be boolean');
    state[key]=value;apply();return api;
  };
  const api={
    setEnabled:value=>set('enabled',value),setSolidControls:value=>set('solidControls',value),
    setViewport(width,height,pixelRatio=1) {
      if(![width,height,pixelRatio].every(v=>Number.isFinite(v)&&v>0))throw new RangeError('Viewport dimensions must be positive');
      lines.uniforms.resolution.value.set(width*pixelRatio,height*pixelRatio);lines.uniforms.lineWidth.value=1.8*pixelRatio;
    },
    getState:()=>({...state}),
    dispose() {
      if(disposed)return;
      state.enabled=false;apply();
      for(const part of parts)part.overlay?.removeFromParent();
      for(const geometry of owned)geometry.dispose();
      for(const material of [surface,lines,instances])material.dispose();
      disposed=true;
    }
  };
  return Object.freeze(api);
}
