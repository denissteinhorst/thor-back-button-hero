// SPDX-License-Identifier: 0BSD
import assert from 'node:assert/strict';
import {HINGE_DETENTS,snapHingeAngle} from './hinge.js';

assert.deepEqual(HINGE_DETENTS,[120,150,180]);
for(const stop of HINGE_DETENTS) {
  for(const delta of [-6,-1,0,1,6])if(stop+delta<=180)assert.equal(snapHingeAngle(stop+delta),stop);
  assert.equal(snapHingeAngle(stop-7),stop-7,'detent catches distant angles');
  if(stop<180)assert.equal(snapHingeAngle(stop+7),stop+7,'cannot leave detent');
}
for(const sequence of [Array.from({length:181},(_,i)=>i),Array.from({length:181},(_,i)=>180-i)]) {
  const result=sequence.map(snapHingeAngle);
  for(let i=0;i<result.length;i++) {
    assert.ok(result[i]>=0&&result[i]<=180);
    assert.equal(snapHingeAngle(result[i]),result[i],'unstable snap');
    if(i)assert.ok((result[i]-result[i-1])*(sequence[i]-sequence[i-1])>=0,'snap reverses opening direction');
  }
}
assert.equal(snapHingeAngle(0),0);assert.equal(snapHingeAngle(100),100);assert.equal(snapHingeAngle(135),135);
for(const value of [-1,181,NaN,Infinity,'120'])assert.throws(()=>snapHingeAngle(value),RangeError);
console.log('Hinge checks passed: 120°/150°/180° capture zones, both travel directions, intermediate angles, endpoints and invalid values.');
