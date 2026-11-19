// SPDX-License-Identifier: 0BSD
import * as THREE from 'three';

const placements=new WeakMap();
// Pin the caption's top-right corner just below the port's lower-left corner.
// Do not push it back into a generic margin: this is an intentional close callout.
export function placeDeviceCallout(root,anchor,camera,caption) {
  root.updateMatrixWorld(true);camera.updateMatrixWorld(true);
  const key=[...camera.matrixWorld.elements,...camera.projectionMatrix.elements,...anchor.matrixWorld.elements,caption.scale.x,caption.scale.y].join(',');
  const cached=placements.get(caption);
  if(cached?.key===key){caption.position.copy(cached.position);return cached.bounds;}
  if(!anchor.geometry.boundingBox)anchor.geometry.computeBoundingBox();
  const box=anchor.geometry.boundingBox;
  const anchorPosition=new THREE.Vector3(box.min.x,box.min.y,box.max.z).applyMatrix4(anchor.matrixWorld);
  const depth=-anchorPosition.clone().applyMatrix4(camera.matrixWorldInverse).z;
  const halfHeight=depth*Math.tan(THREE.MathUtils.degToRad(camera.fov/2))/camera.zoom;
  const labelHalf=caption.scale.y/(2*halfHeight),halfWidth=caption.scale.x/(2*halfHeight*camera.aspect);
  const projected=anchorPosition.project(camera);
  const gap=.006,port=projected.clone();
  projected.x-=halfWidth+gap;projected.y-=labelHalf+gap;
  const bounds={port,labelHalf,halfWidth,gap};
  caption.position.copy(projected.unproject(camera));
  placements.set(caption,{key,position:caption.position.clone(),bounds});return bounds;
}
