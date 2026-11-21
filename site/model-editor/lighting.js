// SPDX-License-Identifier: 0BSD
import * as THREE from 'three';

const keyOffset=new THREE.Vector3(-95,150,155);

// OrbitControls moves the camera around a stationary device. Keep the studio key
// in the viewing frame so the shadow changes as the user inspects another side.
export function updateStudioKey(light,camera,target) {
  light.position.copy(keyOffset).applyQuaternion(camera.quaternion).add(target);
  light.target.position.copy(target);
  light.target.updateMatrixWorld();
}
