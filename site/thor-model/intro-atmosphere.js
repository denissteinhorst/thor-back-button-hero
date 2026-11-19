// SPDX-License-Identifier: 0BSD
const clamp=value=>Math.max(0,Math.min(1,value));

export function sampleAtmosphere(time) {
  return {x:.008*Math.sin(time*.6),y:.003*Math.sin(time*.35),zoom:1.025+.008*Math.sin(time*.4),lava:.22+.12*Math.sin(time*1.8)};
}

const flights=[
  [-930,-650,0,26],[-670,-670,.01,20],[790,-620,.025,27],
  [510,-710,.04,18],[-350,-730,.06,22]
];
export function sampleBirds(elapsed) {
  if(elapsed<=0)return [];
  const progress=clamp(elapsed/.9);
  return flights.map(([dx,dy,delay,size],index)=>{
    const p=clamp((progress-delay)/(1-delay));
    return {x:768+dx*p,y:432+dy*p-45*Math.sin(p*Math.PI),size:size*(1-.35*p),
      wing:Math.sin(elapsed*Math.PI*11+index*.8),opacity:clamp(p*8),active:p>0};
  }).filter(bird=>bird.active);
}

export function drawBirds(context,elapsed) {
  context.save();context.strokeStyle='#172b34';context.lineCap='round';context.lineJoin='round';
  for(const bird of sampleBirds(elapsed)){
    const {x,y,size,wing}=bird;context.globalAlpha=bird.opacity;context.lineWidth=Math.max(2,size*.12);
    context.beginPath();context.moveTo(x-size,y-wing*size*.65);
    context.quadraticCurveTo(x-size*.4,y-size*.2,x,y+size*.16);
    context.quadraticCurveTo(x+size*.4,y-size*.2,x+size,y-wing*size*.65);context.stroke();
  }
  context.restore();
}
