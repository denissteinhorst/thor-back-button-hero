// SPDX-License-Identifier: 0BSD
import {sample} from './thor-ad-v2/timeline.js';

// Stable equivalents of the film's Back, shared-Home and Dual-mode shots.
export const MODES=Object.freeze({
  back:Object.freeze({filmTime:6.8,title:'Hold Back. Bind every other button.',detail:'Buttons, D-pad and sticks.'}),
  shared:Object.freeze({filmTime:11.2,title:'Home becomes a second Back.',detail:'Either hand. The same hotkeys.'}),
  dual:Object.freeze({filmTime:16.4,title:'Dual mode. Twice the hotkeys.',detail:'Two independent hotkey layers.'})
});
export const PINK='#d64d96';
export const GREEN='#66ff00';
export const BACK_HEIGHT=24;
export const HOME_HEIGHT=67;
export function frameForMode(mode){
  const id=Object.hasOwn(MODES,mode)?mode:'back';
  const film=sample(MODES[id].filmTime);
  return {
    id,back:film.panes[0],home:film.panes[1],
    homeEnabled:id==='back'?0:1,independent:id==='dual'?1:0,
    description:id==='back'?'Holding Back enables a hotkey layer for every other gamepad control.':id==='shared'?'Back and Home both enable the same hotkey layer.':'Back and Home enable two independent hotkey layers.'
  };
}
