// SPDX-License-Identifier: 0BSD
import * as THREE from 'three';

// Planar corner radius and edge fillet must remain independent on thin glass/lids.
export function roundedOutline(width, height, radius, cx = 0, cy = 0) {
  const s = new THREE.Shape();
  const x = cx - width / 2, y = cy - height / 2;
  const r = Math.min(radius, width / 2, height / 2);
  s.moveTo(x+r,y);s.lineTo(x+width-r,y);
  s.quadraticCurveTo(x+width,y,x+width,y+r);
  s.lineTo(x+width,y+height-r);s.quadraticCurveTo(x+width,y+height,x+width-r,y+height);
  s.lineTo(x+r,y+height);s.quadraticCurveTo(x,y+height,x,y+height-r);
  s.lineTo(x,y+r);s.quadraticCurveTo(x,y,x+r,y);
  return s;
}

export function circularHole(shape, x, y, r) {
  const p = new THREE.Path();p.absarc(x,y,r,0,Math.PI*2,true);shape.holes.push(p);
}

export function capsuleOutline(width, height, cx=0, cy=0) {
  const r=height/2, left=cx-width/2+r, right=cx+width/2-r;
  const s=new THREE.Shape();s.moveTo(left,cy-r);s.lineTo(right,cy-r);
  s.absarc(right,cy,r,-Math.PI/2,Math.PI/2,false);s.lineTo(left,cy+r);
  s.absarc(left,cy,r,Math.PI/2,Math.PI*1.5,false);return s;
}

export function capsuleHole(shape,x,y,width,height) {
  shape.holes.push(new THREE.Path(capsuleOutline(width,height,x,y).getPoints(24).reverse()));
}

export function slotHole(shape, x, y, width, height, radius = height / 2) {
  const p = roundedOutline(width,height,radius,x,y);
  shape.holes.push(new THREE.Path(p.getPoints(8).reverse()));
}

export function controlOutline(width=147.2, height=80.8, radius=6.1, cy=-5.6) {
  const x=width/2, front=cy-height/2, rear=cy+height/2, r=radius;
  const s=new THREE.Shape();s.moveTo(-x+r,front);s.lineTo(-28,front);
  s.lineTo(-28,-42.1);s.quadraticCurveTo(-28,-41,-26.8,-41);
  s.lineTo(26.8,-41);s.quadraticCurveTo(28,-41,28,-42.1);
  s.lineTo(28,front);s.lineTo(x-r,front);s.quadraticCurveTo(x,front,x,front+r);
  s.lineTo(x,rear-r);s.quadraticCurveTo(x,rear,x-r,rear);
  s.lineTo(-x+r,rear);s.quadraticCurveTo(-x,rear,-x,rear-r);
  s.lineTo(-x,front+r);s.quadraticCurveTo(-x,front,-x+r,front);return s;
}

export function extrude(shape, depth, bevel = .15) {
  const g = new THREE.ExtrudeGeometry(shape, {
    depth, bevelEnabled: bevel > 0, bevelThickness: bevel, bevelSize: bevel,
    bevelSegments: 3, curveSegments: 20, steps: 1
  });
  g.translate(0,0,-depth/2);return g;
}

// The front bridge tapers into the shell across broad moulded ramps. The top
// inclines gently toward the player; a box with an edge bevel misses that profile.
export const AYN_SLOPE=THREE.MathUtils.degToRad(3);
export function aynBridgeGeometry() {
  const profiles=[
    [60.4,6.3,1.45,7.12], [60.2,6.25,1.45,7.65],
    [59.7,6.18,1.4,8.12], [55.4,5.1,1.05,9.62],
    [54.8,4.9,1,9.82], [54.5,4.82,.98,9.85]
  ];
  const rings=profiles.map(([w,d,r,y])=>roundedOutline(w,d,r).getPoints(20).slice(0,-1).map(p=>{
    const z=43.8-p.y;
    return new THREE.Vector3(p.x,y-Math.tan(AYN_SLOPE)*(z-43.25),z);
  }));
  const positions=rings.flatMap(r=>r.flatMap(p=>p.toArray())),indices=[],n=rings[0].length;
  for(let k=0;k<rings.length-1;k++)for(let i=0;i<n;i++) {
    const j=(i+1)%n,a=k*n+i,b=k*n+j;
    indices.push(a,b,a+n,b,b+n,a+n);
  }
  const contour=rings[0].map(p=>new THREE.Vector2(p.x,-p.z));
  for(const [a,b,c] of THREE.ShapeUtils.triangulateShape(contour,[])) {
    indices.push(c,b,a);
    const offset=(rings.length-1)*n;indices.push(offset+a,offset+b,offset+c);
  }
  const g=new THREE.BufferGeometry();g.setAttribute('position',new THREE.Float32BufferAttribute(positions,3));
  g.setIndex(indices);g.computeVertexNormals();return g;
}

export function shoulderRecess(x,y,z) {
  const smooth=(a,b,v)=>{const t=THREE.MathUtils.clamp((v-a)/(b-a),0,1);return t*t*(3-2*t);};
  const pocket=smooth(50,55,Math.abs(x))*(1-smooth(-40,-31,z))
    *smooth(-10.6,-8,y)*(1-smooth(4.5,7.2,y));
  const corner=smooth(67,74,Math.abs(x));
  // The lower shoulder cheek swells back out beneath the trigger. A uniform
  // recess erased this moulded cup and made the entire corner look scooped away.
  const cheek=2.3*smooth(-10,-7,y)*(1-smooth(-2,1,y));
  return new THREE.Vector3(x-Math.sign(x)*.35*pocket*corner,y+undersideRelief(x,y,z),z+(3.8-cheek)*pocket);
}

// Maximum thickness belongs to the rounded rear shoulder cheeks. The broad
// central underside sits slightly inward; a completely flat plate erased them.
export function undersideRelief(x,y,z) {
  const cheek=THREE.MathUtils.smoothstep(Math.abs(x),47,61)
    *Math.exp(-(((Math.abs(x)-64)/18)**2+((z+39)/17)**2));
  // Rear photos show a pronounced dome whose crown sits inside the back panel,
  // not merely a raised perimeter. Keep the broad panel fixed and swell locally.
  return (3.1-5.2*cheek)*(1-THREE.MathUtils.smoothstep(y,-10.6,-5.1));
}

export function recessShoulderSurface(geometry) {
  const p=geometry.attributes.position;
  for(let i=0;i<p.count;i++) {
    const v=shoulderRecess(p.getX(i),p.getY(i),p.getZ(i));p.setXYZ(i,v.x,v.y,v.z);
  }
  geometry.computeVertexNormals();return geometry;
}

// A custom loft preserves a flat deck, a tall sidewall and a softer underside.
// No front-wall faces behind the port panel: its apertures remain open.
export function shellWall(underside) {
  const profile = [
    [141.6,85.6,6.8,-10.5], [145.8,90,7.1,-10.05],
    [148.6,92.8,7.2,-8.5], [150,94,7.2,-5.5],
    [150,94,7.2,5.9], [149.7,93.7,7.1,7.2]
  ];
  const levels=[];
  for(let k=0;k<profile.length-1;k++) {
    const a=profile[k],b=profile[k+1],steps=Math.ceil((b[3]-a[3])/.7);
    for(let j=0;j<steps;j++)levels.push(a.map((v,i)=>THREE.MathUtils.lerp(v,b[i],j/steps)));
  }
  levels.push(profile.at(-1));
  // Subdivide straight walls too, so the recessed shoulder regions remain
  // continuous with their neighbouring rear/corner surfaces.
  const rings=levels.map(([w,d,r,y])=>{
    const points=roundedOutline(w,d,r).curves.flatMap(c=>c.getPoints(c.type==='LineCurve'?128:32).slice(0,-1));
    return points.map(p=>new THREE.Vector3(p.x,y,-p.y));
  });
  const positions=rings.flatMap(r=>r.flatMap(p=>p.toArray()));
  const indices=[], n=rings[0].length;
  for(let k=0;k<rings.length-1;k++)for(let i=0;i<n;i++) {
    const j=(i+1)%n;
    const a=rings[k][i],b=rings[k][j],c=rings[k+1][i],d=rings[k+1][j];
    if(a.y>=-5.5 && Math.max(a.y,b.y,c.y,d.y)<=5.91 && Math.min(a.z,b.z,c.z,d.z)>46 && Math.max(Math.abs(a.x),Math.abs(b.x))<68)continue;
    const start=k*n+i,next=k*n+j;indices.push(start,next,start+n,next,next+n,start+n);
  }
  // Share the exact outer boundary with the underside. Separate overlapping
  // plates left a bright cracked-looking seam and did not prove a closed join.
  const outer=rings[0].map(p=>new THREE.Vector2(p.x,-p.z));
  const holes=underside.holes.map(path=>{
    const points=path.getPoints(20);
    if(points[0].distanceTo(points.at(-1))<1e-6)points.pop();return points;
  });
  const capVertices=outer.map((_,i)=>i);
  for(const hole of holes)for(const p of hole) {
    capVertices.push(positions.length/3);positions.push(p.x,profile[0][3],-p.y);
  }
  for(const [a,b,c]of triangulateBoundary(outer,holes))indices.push(capVertices[c],capVertices[b],capVertices[a]);
  // Vent walls turn inward into the case; their far edges remain open as real
  // apertures, backed by the separate fan/exhaust recesses.
  let capOffset=n;
  for(const hole of holes) {
    const top=hole.map(p=>{const id=positions.length/3;positions.push(p.x,profile[0][3]+.28,-p.y);return id;});
    for(let i=0;i<hole.length;i++) {
      const j=(i+1)%hole.length,a=capVertices[capOffset+i],b=capVertices[capOffset+j];
      indices.push(a,top[i],b,b,top[i],top[j]);
    }
    capOffset+=hole.length;
  }
  const source=new THREE.BufferGeometry();source.setAttribute('position',new THREE.Float32BufferAttribute(positions,3));source.setIndex(indices);
  const triangles=source.toNonIndexed();
  // Subdivide the underside across its full span: long triangles running out
  // of a dome otherwise bridge over the flat panel and obscure its markings.
  const g=refineSolid(triangles,2,(a,b)=>Math.min(a.y,b.y)<-10.2);
  source.dispose();triangles.dispose();return recessShoulderSurface(g);
}

// Earcut may omit collinear outline samples. Reinsert them into boundary
// triangles so the densely sampled sidewall and cap have identical edges.
function triangulateBoundary(outer,holes) {
  const loops=[outer,...holes],points=loops.flat(),membership=[];
  let offset=0;
  for(const loop of loops) {
    for(let i=0;i<loop.length;i++)membership.push({offset,length:loop.length,index:i});
    offset+=loop.length;
  }
  const path=(a,b)=>{
    const m=membership[a],n=membership[b];if(m.offset!==n.offset)return null;
    const ab=points[b].clone().sub(points[a]),length=ab.lengthSq();
    for(const step of [1,-1]) {
      const result=[a];
      for(let k=1;k<m.length;k++) {
        const id=m.offset+(m.index+step*k+m.length)%m.length;
        if(id===b){result.push(b);if(result.length>2)return result;break;}
        const ap=points[id].clone().sub(points[a]),dot=ap.dot(ab);
        if(Math.abs(ap.x*ab.y-ap.y*ab.x)>1e-6||dot<=0||dot>=length)break;
        result.push(id);
      }
    }
    return null;
  };
  const pending=THREE.ShapeUtils.triangulateShape(outer,holes),faces=[];
  while(pending.length) {
    const triangle=pending.pop();let split=false;
    for(let edge=0;edge<3;edge++) {
      const a=triangle[edge],b=triangle[(edge+1)%3],c=triangle[(edge+2)%3],chain=path(a,b);
      if(!chain)continue;
      for(let i=0;i<chain.length-1;i++)pending.push([chain[i],chain[i+1],c]);
      split=true;break;
    }
    if(!split)faces.push(triangle);
  }
  return faces;
}

export function bowlGeometry() {
  // Profile from floor to lip, with an open centre for the stick stem.
  return new THREE.LatheGeometry([
    new THREE.Vector2(3.3,3.5),new THREE.Vector2(7.6,3.5),
    new THREE.Vector2(9.1,3.8),new THREE.Vector2(10.2,4.6),
    new THREE.Vector2(11.2,5.8),new THREE.Vector2(12,7.1),
    new THREE.Vector2(12.6,7.62)
  ].reverse(),96);
}

export function dpadGeometry() {
  // One connected rocker, including the material below the diagonal grooves.
  const s=new THREE.Shape();s.moveTo(-2.65,9.05);s.lineTo(2.65,9.05);
  s.quadraticCurveTo(3.4,9.05,3.4,8.3);s.lineTo(3.4,4.05);s.lineTo(4.05,3.4);s.lineTo(8.3,3.4);
  s.quadraticCurveTo(9.05,3.4,9.05,2.65);s.lineTo(9.05,-2.65);
  s.quadraticCurveTo(9.05,-3.4,8.3,-3.4);s.lineTo(4.05,-3.4);s.lineTo(3.4,-4.05);s.lineTo(3.4,-8.3);
  s.quadraticCurveTo(3.4,-9.05,2.65,-9.05);s.lineTo(-2.65,-9.05);
  s.quadraticCurveTo(-3.4,-9.05,-3.4,-8.3);s.lineTo(-3.4,-4.05);s.lineTo(-4.05,-3.4);s.lineTo(-8.3,-3.4);
  s.quadraticCurveTo(-9.05,-3.4,-9.05,-2.65);s.lineTo(-9.05,2.65);
  s.quadraticCurveTo(-9.05,3.4,-8.3,3.4);s.lineTo(-4.05,3.4);s.lineTo(-3.4,4.05);s.lineTo(-3.4,8.3);
  s.quadraticCurveTo(-3.4,9.05,-2.65,9.05);
  const source=extrude(s,1.05,.28), g=refineSolid(source,.45);source.dispose();
  const p=g.attributes.position;
  for(let i=0;i<p.count;i++) {
    const distance=Math.abs(Math.abs(p.getX(i))-Math.abs(p.getY(i)))/Math.SQRT2;
    const groove=.58*(1-THREE.MathUtils.smoothstep(distance,.12,.7));
    const upper=THREE.MathUtils.smoothstep(p.getZ(i),-.2,.525);
    p.setZ(i,p.getZ(i)-groove*upper);
  }
  g.computeVertexNormals();return g;
}

export function triggerOutline() {
  const s=new THREE.Shape();s.moveTo(-10.1,0);s.lineTo(10.1,0);
  s.bezierCurveTo(10.6,-2.9,8.6,-6.5,5.3,-8.4);
  s.bezierCurveTo(2.2,-10.3,-2.2,-10.3,-5.3,-8.4);
  s.bezierCurveTo(-8.6,-6.5,-10.6,-2.9,-10.1,0);s.closePath();return s;
}

export function bumperOutline() {
  const s=new THREE.Shape();s.moveTo(-9.1,-1.9);
  s.quadraticCurveTo(-10.4,-1.9,-10.4,-.6);s.lineTo(-10.4,.9);
  s.quadraticCurveTo(-10.4,2.05,-9.1,2.1);
  s.quadraticCurveTo(0,2.75,9.1,2.1);s.quadraticCurveTo(10.4,2.05,10.4,.9);
  s.lineTo(10.4,-.6);s.quadraticCurveTo(10.4,-1.9,9.1,-1.9);s.closePath();return s;
}

export function shoulderGeometry(shape, depth, side, bevel=.25) {
  const source=extrude(shape,depth,bevel);
  const g=refineSolid(source,1.1);source.dispose();
  return bendShoulder(g,side);
}

// One closed solid: a thin overhanging contact paddle steps into a narrower
// moving stem. The video shows daylight around that stem, not a full-depth plug.
export function triggerGeometry(side) {
  const contour=triggerOutline().getPoints(32).slice(0,-1),centre=-4.4;
  const sections=[[-3.45,.99,.99],[-3.36,1,1],[-2.72,1,1],[-2.54,.985,.985],
    [-2.3,.74,.7],[2.85,.74,.7],[3.2,.71,.67]];
  const points=sections.flatMap(([z,sx,sy])=>contour.flatMap(p=>[p.x*sx,centre+(p.y-centre)*sy,z]));
  const indices=[],n=contour.length;
  for(let k=0;k<sections.length-1;k++)for(let i=0;i<n;i++) {
    const j=(i+1)%n,a=k*n+i,b=k*n+j;
    indices.push(a,a+n,b,b,a+n,b+n);
  }
  for(const [a,b,c]of THREE.ShapeUtils.triangulateShape(contour,[])) {
    indices.push(c,b,a);const offset=(sections.length-1)*n;indices.push(offset+a,offset+b,offset+c);
  }
  const source=new THREE.BufferGeometry();source.setAttribute('position',new THREE.Float32BufferAttribute(points,3));source.setIndex(indices);
  const triangles=source.toNonIndexed(),g=refineSolid(triangles,.8);source.dispose();triangles.dispose();
  return bendShoulder(g,side);
}

function bendShoulder(g,side) {
  const p=g.attributes.position;
  for(let i=0;i<p.count;i++) {
    const outer=Math.max(0,Math.abs(side*64+p.getX(i))-67.5);
    // The outer third wraps forward around the shell corner. The shallow crown
    // rounds the contact face without turning the trigger into a thick D-shaped block.
    const crown=.007*p.getX(i)**2;
    p.setZ(i,p.getZ(i)+outer*outer*.085+crown);
  }
  g.computeVertexNormals();return g;
}

// A non-linear bend needs interior face vertices, not only a sampled outline.
// Shared-edge refinement avoids T-junctions between cap faces, fillets and walls.
function refineSolid(source,maxEdge,needsRefinement=()=>true) {
  const vertices=[], faces=[], welded=new Map(), sourcePosition=source.attributes.position;
  const vertex=i=>{
    const p=new THREE.Vector3().fromBufferAttribute(sourcePosition,i);
    const key=p.toArray().map(v=>Math.round(v*1e5)).join(',');
    if(!welded.has(key)){welded.set(key,vertices.length);vertices.push(p);}
    return welded.get(key);
  };
  for(let i=0;i<sourcePosition.count;i+=3) {
    const ids=[vertex(i),vertex(i+1),vertex(i+2)];
    if(new Set(ids).size===3)faces.push(ids);
  }
  let triangles=faces;
  for(let pass=0;pass<10;pass++) {
    const midpoints=new Map();
    const key=(a,b)=>a<b?a+':'+b:b+':'+a;
    for(const [a,b,c] of triangles)for(const [u,v] of [[a,b],[b,c],[c,a]]) {
      const edge=key(u,v);
      if(!midpoints.has(edge)&&vertices[u].distanceToSquared(vertices[v])>maxEdge**2&&needsRefinement(vertices[u],vertices[v])) {
        midpoints.set(edge,vertices.length);vertices.push(vertices[u].clone().add(vertices[v]).multiplyScalar(.5));
      }
    }
    if(!midpoints.size)break;
    const next=[];
    for(const [a,b,c] of triangles) {
      const ab=midpoints.get(key(a,b)),bc=midpoints.get(key(b,c)),ca=midpoints.get(key(c,a));
      const mask=Number(ab!==undefined)+Number(bc!==undefined)*2+Number(ca!==undefined)*4;
      if(mask===0)next.push([a,b,c]);
      if(mask===1)next.push([a,ab,c],[ab,b,c]);
      if(mask===2)next.push([a,b,bc],[a,bc,c]);
      if(mask===4)next.push([a,b,ca],[ca,b,c]);
      if(mask===3)next.push([ab,b,bc],[a,ab,c],[ab,bc,c]);
      if(mask===6)next.push([bc,c,ca],[a,b,ca],[b,bc,ca]);
      if(mask===5)next.push([ca,a,ab],[ab,b,c],[ca,ab,c]);
      if(mask===7)next.push([a,ab,ca],[ab,b,bc],[ca,bc,c],[ab,bc,ca]);
    }
    triangles=next;
  }
  const g=new THREE.BufferGeometry();
  g.setAttribute('position',new THREE.Float32BufferAttribute(vertices.flatMap(v=>v.toArray()),3));
  g.setIndex(triangles.flat());return g;
}

export function hingeCheekGeometry() {
  // Inner end remains a joint; the outside closes in a rounded nose instead of
  // the flat circular end of a cylinder. Axis is local y, outer end is positive.
  return new THREE.LatheGeometry([
    [0,-9.65],[4.15,-9.65],[4.35,-9.35],[4.35,5.1],
    [4.3,6.1],[4.05,7.2],[3.55,8.2],[2.7,9],[1.5,9.48],[0,9.65]
  ].map(([r,y])=>new THREE.Vector2(r,y)),96);
}

export function caseJoinGeometry() {
  // Sample the full outline before deforming, including the straight rear edge.
  // A cap triangulated across the old outline would leave fins through the pocket.
  const sample=shape=>shape.curves.flatMap(c=>c.getPoints(c.type==='LineCurve'?128:16).slice(0,-1));
  const outer=sample(roundedOutline(149.9,93.9,7.2));
  const inner=sample(roundedOutline(149.3,93.3,7));
  const rings=[[outer,-5.01],[outer,-4.79],[inner,-4.79],[inner,-5.01]];
  const vertices=rings.flatMap(([points,y])=>points.flatMap(p=>[p.x,y,-p.y]));
  const indices=[], n=outer.length;
  for(let ring=0;ring<4;ring++)for(let i=0;i<n;i++){
    const j=(i+1)%n, next=(ring+1)%4;
    const a=ring*n+i,b=ring*n+j,c=next*n+i,d=next*n+j;
    indices.push(a,b,c,b,d,c);
  }
  const g=new THREE.BufferGeometry();g.setAttribute('position',new THREE.Float32BufferAttribute(vertices,3));g.setIndex(indices);
  return recessShoulderSurface(g);
}
