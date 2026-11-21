// SPDX-License-Identifier: 0BSD
// Every assignable input has its own annotation. Offsets are in model space;
// spreading a label never changes the physical point its leader starts from.
export const CONTROLS=[
 ...['A','B','X','Y'].map(name=>({name,joint:name,label:name,radius:4.5})),
 ...[['Up','↑',0,-6.25,0,-11],['Right','→',6.25,0,11,0],['Down','↓',0,6.25,0,11],['Left','←',-6.25,0,-11,0]].map(([direction,label,x,z,tx,tz])=>({name:'dpad'+direction,joint:'dpad',label,anchor:[x,z],spread:[tx-x,tz-z],radius:3.4})),
 ...['select','start','home'].map(name=>({name,joint:name,label:{select:'SEL',start:'START',home:'HOME'}[name],radius:4.5})),
 ...['left','right'].flatMap((side)=>[
  ['Up','↑',0,-16],['Right','→',16,0],['Down','↓',0,16],['Left','←',-16,0],['Press','1×',-9,8],['Double','2×',0,5],['Hold','H',9,8]
 ].map(([input,label,x,z])=>({name:side+'Stick'+input,joint:side+'Stick',label,spread:[x+(side==='left'?-8:8),z+(side==='left'?-11:12)],radius:3.1,stick:side}))),
 ...['L1','L2','R1','R2'].map(name=>({name,joint:name,label:name,spread:[name[0]==='L'?-12:12,name[1]==='1'?-15:11],radius:5.5,shoulder:true}))
];
export function controlAnchor(T,root,control){
 const joint=root.getObjectByName(control.joint+'-joint');
 const bounds=new T.Box3().setFromObject(joint),point=bounds.getCenter(new T.Vector3());
 point.y=bounds.max.y+.7;
 if(control.anchor){point.x=joint.position.x+control.anchor[0];point.z=joint.position.z+control.anchor[1];}
 return point;
}
