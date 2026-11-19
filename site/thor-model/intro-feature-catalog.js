// SPDX-License-Identifier: 0BSD
// Editorial grouping of the owner's 33 supplied actions. Short labels retain
// each distinct function; configuration variants stay out of the ad's overview.
export const FEATURE_GROUPS=[
  {title:'Master both screens',benefit:'Apps and input, in sync.',items:[
    ['Bottom-screen app switcher'],['Swap both apps'],['Move active app'],['Controller focus'],['Display power'],['Launch any app']
  ]},
  {title:'Play your way',benefit:'Make every input yours.',items:[
    ['Gyro aiming'],['Virtual mouse'],['Macro recorder'],['Controller layout'],['Stick lighting'],['Vibration']
  ]},
  {title:'Capture & connect',benefit:'Keep the game going.',items:[
    ['Screenshots'],['Gameplay + push-to-talk'],['Media controls'],['Mute microphone']
  ]},
  {title:'Tune your Thor',benefit:'Power, cooling, clarity.',items:[
    ['Performance mode'],['Fan control'],['60 / 120 Hz'],['Direct power supply'],['Status overlay']
  ]},
  {title:'Stay in control',benefit:'Less interruption. More play.',items:[
    ['Volume & mute'],['Brightness'],['Minimize / exit / restart'],['Close apps'],['Do Not Disturb'],['Force landscape']
  ]},
  {title:'Android, one press away',benefit:'Everyday actions, closer.',items:[
    ['Regular Back'],['Home'],['Navigation flick'],['System panels'],['Power menu / standby'],['System settings']
  ]}
];
export const FEATURE_COUNT=FEATURE_GROUPS.reduce((sum,group)=>sum+group.items.length,0);
