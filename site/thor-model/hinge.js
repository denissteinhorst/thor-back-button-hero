// SPDX-License-Identifier: 0BSD
export const HINGE_DETENTS=Object.freeze([120,150,180]);

// A small capture zone gives each mechanical stop a crisp arrival while keeping
// intermediate opening angles available for inspecting the reconstruction.
export function snapHingeAngle(degrees) {
  if(!Number.isFinite(degrees)||degrees<0||degrees>180)throw new RangeError('Hinge angle must be between 0 and 180 degrees');
  return HINGE_DETENTS.find(stop=>Math.abs(stop-degrees)<=6)??degrees;
}
