// SPDX-License-Identifier: 0BSD
export function mountPoseInspector(readPose) {
  const data=document.getElementById('pose-data'),copy=document.getElementById('pose-copy');
  const status=document.getElementById('pose-copy-status');
  let previous='';
  function refresh() {
    const json=JSON.stringify(readPose(),null,2);
    // Preserve the user's text selection while the scene is stationary.
    if(json!==previous){data.value=json;previous=json;}
    copy.disabled=false;
    return json;
  }
  copy.addEventListener('click',async()=>{
    const json=refresh();
    try {
      await navigator.clipboard.writeText(json);
      status.textContent='Position kopiert.';
    }catch{
      data.focus();data.select();
      status.textContent='Bitte den markierten Text mit Strg+C oder ⌘C kopieren.';
    }
  });
  refresh();
  return refresh;
}
