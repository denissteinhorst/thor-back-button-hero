// SPDX-License-Identifier: 0BSD
// Keep authored rig cues intact while giving the advertisement a deliberate
// 30-second edit: setup, pause demo, inputs, eight hero beats, final poster.
const cuts=[[0,0],[18,11.2],[23,14.2],[31.8,18],[43.8,26],[47,30]];
function mapTime(value,from,to){
  const t=Math.max(0,Math.min(cuts.at(-1)[from],value));
  for(let i=1;i<cuts.length;i++)if(t<=cuts[i][from]){
    const a=cuts[i-1],b=cuts[i],p=(t-a[from])/(b[from]-a[from]);
    return a[to]+p*(b[to]-a[to]);
  }
  return cuts.at(-1)[to];
}
export const storyToAd=time=>mapTime(time,0,1);
export const adToStory=time=>mapTime(time,1,0);
