// SPDX-License-Identifier: 0BSD
const labels={A:'A',B:'B',X:'X',Y:'Y',select:'Select',start:'Start',home:'Home',back:'Back',AYN:'AYN',power:'Power',
  volumeDown:'Lautstärke −',volumeUp:'Lautstärke +',L1:'L1',R1:'R1',L2:'L2',R2:'R2',L3:'L3',R3:'R3',
  dpadUp:'D-Pad ↑',dpadDown:'D-Pad ↓',dpadLeft:'D-Pad ←',dpadRight:'D-Pad →'};

export function mountInputInspector(inputs) {
  const select=document.getElementById('input-button'),pressure=document.getElementById('input-pressure');
  const stick=document.getElementById('input-stick'),x=document.getElementById('input-stick-x'),y=document.getElementById('input-stick-y');
  for(const name of inputs.buttons){const option=document.createElement('option');option.value=name;option.textContent=labels[name];select.append(option);}
  function refresh(state) {
    pressure.value=String(Math.round(state.buttons[select.value]*100));
    document.getElementById('input-pressure-value').value=pressure.value+' %';
    x.value=String(Math.round(state.sticks[stick.value].x*100));y.value=String(Math.round(state.sticks[stick.value].y*100));
    document.getElementById('input-stick-x-value').value=x.value+' %';document.getElementById('input-stick-y-value').value=y.value+' %';
    const active=inputs.buttons.filter(name=>state.buttons[name]>0);
    document.getElementById('input-state').textContent=active.length?'Gedrückt: '+active.map(name=>labels[name]).join(', '):'Alle Tasten losgelassen';
  }
  inputs.subscribe(refresh);
  select.addEventListener('change',()=>refresh(inputs.getState()));
  stick.addEventListener('change',()=>refresh(inputs.getState()));
  pressure.addEventListener('input',()=>inputs.setButton(select.value,Number(pressure.value)/100));
  x.addEventListener('input',()=>inputs.setStick(stick.value,{x:Number(x.value)/100}));
  y.addEventListener('input',()=>inputs.setStick(stick.value,{y:Number(y.value)/100}));
  document.getElementById('input-stick-centre').addEventListener('click',()=>inputs.setStick(stick.value,{x:0,y:0}));
  document.getElementById('input-release').addEventListener('click',()=>inputs.releaseAll());
}
