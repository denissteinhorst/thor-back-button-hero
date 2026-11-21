// SPDX-License-Identifier: 0BSD
// The public player shares the film renderer, with playback controls only.
const canvas = document.getElementById('film');
const context = canvas.getContext('2d');
const playButton = document.getElementById('play');
const fullscreenButton = document.getElementById('fullscreen');
const seek = document.getElementById('seek');
const clock = document.getElementById('time');
const status = document.getElementById('status');
let time = 0;
let playing = false;
let origin = 0;
let anchor = 0;
let frame;
let render;
const duration = 30;

function updateControls() {
  playButton.textContent = playing ? 'Pause' : time >= duration ? 'Replay' : 'Play';
  seek.value = time;
  seek.setAttribute('aria-valuetext', `${Math.floor(time)} of ${duration} seconds`);
  clock.textContent = `0:${String(Math.floor(time)).padStart(2, '0')} / 0:30`;
}

function pause() {
  playing = false;
  cancelAnimationFrame(frame);
  updateControls();
}

function tick(now) {
  time = Math.min(duration, origin + (now - anchor) / 1000);
  render(time);
  updateControls();
  if (time >= duration) pause();
  else frame = requestAnimationFrame(tick);
}

playButton.addEventListener('click', () => {
  if (playing) return pause();
  if (time >= duration) time = 0;
  origin = time;
  anchor = performance.now();
  playing = true;
  updateControls();
  frame = requestAnimationFrame(tick);
});

seek.addEventListener('input', () => {
  const position = Number(seek.value);
  pause();
  time = position;
  render(time);
  updateControls();
});

fullscreenButton.addEventListener('click', async () => {
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else await document.querySelector('.film-player').requestFullscreen();
  } catch {
    fullscreenButton.textContent = 'Full screen unavailable';
    fullscreenButton.disabled = true;
  }
});
document.addEventListener('fullscreenchange', () => {
  fullscreenButton.textContent = document.fullscreenElement ? 'Exit full screen' : 'Full screen';
});
document.addEventListener('visibilitychange', () => { if (document.hidden) pause(); });
window.addEventListener('pagehide', pause);
// An embedded film also pauses when its section scrolls out of view.
new IntersectionObserver(entries => {
  if (!entries[0].isIntersecting) pause();
}).observe(canvas);

try {
  const [{createStudio}, {draw}, {sample}] = await Promise.all([
    import('./studio.js?v=hierarchy-17'),
    import('./graphics.js?v=hierarchy-17'),
    import('./timeline.js?v=hierarchy-17')
  ]);
  const logo = new Image();
  logo.src = '../assets/icon.png';
  const [studio] = await Promise.all([createStudio(), logo.decode()]);
  render = position => draw(context, sample(position), studio, logo);
  // Show the opening title and device while waiting for an explicit Play.
  render(2.8);
  status.hidden = true;
  playButton.disabled = false;
  seek.disabled = false;
  fullscreenButton.disabled = !document.fullscreenEnabled;
} catch (error) {
  status.textContent = 'This film could not load. Please try a browser with WebGL support.';
  console.error('Film player:', error);
}
