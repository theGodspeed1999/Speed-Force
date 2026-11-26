// Search by image: the picture is only held in memory. It is redrawn smaller (which also drops the
// location and camera details stored inside photo files) and posted to Google Lens, whose results open here.
'use strict';
const MAX_SIDE = 1280;
const $ = id => document.getElementById(id);
const say = text => { $('msg').textContent = text; };

async function search(file) {
  if (!file || !file.type.startsWith('image/')) { say('That is not a picture. Drop, paste or choose an image file.'); return; }
  say('Searching with Google Lens…');
  try {
    const bmp = await createImageBitmap(file);
    const k = Math.min(1, MAX_SIDE / Math.max(bmp.width, bmp.height));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(bmp.width * k));
    canvas.height = Math.max(1, Math.round(bmp.height * k));
    const g = canvas.getContext('2d');
    g.fillStyle = '#fff'; // see-through parts become white, not black
    g.fillRect(0, 0, canvas.width, canvas.height);
    g.drawImage(bmp, 0, 0, canvas.width, canvas.height);
    bmp.close();
    const small = await new Promise(done => canvas.toBlob(done, 'image/jpeg', 0.85));
    canvas.width = canvas.height = 0;
    const box = new DataTransfer();
    box.items.add(new File([small], 'image.jpg', { type: 'image/jpeg' }));
    $('out').files = box.files;
    $('send').action = 'https://lens.google.com/v3/upload?hl=en&ep=ccm&s=&st=' + Date.now();
    $('send').submit();
  } catch (e) { say('This picture could not be read. Try a JPG or PNG file.'); }
}

$('browse').addEventListener('click', () => $('pick').click());
$('pick').addEventListener('change', () => { const f = $('pick').files[0]; $('pick').value = ''; if (f) search(f); });
addEventListener('paste', e => {
  const f = [...(e.clipboardData ? e.clipboardData.files : [])].find(x => x.type.startsWith('image/'));
  e.preventDefault();
  if (f) search(f); else say('There is no picture on the clipboard.');
});
addEventListener('dragover', e => { e.preventDefault(); $('zone').classList.add('over'); });
addEventListener('dragleave', e => { if (!e.relatedTarget) $('zone').classList.remove('over'); });
addEventListener('drop', e => {
  e.preventDefault();
  $('zone').classList.remove('over');
  const f = e.dataTransfer.files[0];
  if (f) search(f); else say('Drop an image file. For a picture on a web page, copy it and paste it here.');
});
// coming back to this page: nothing of the last picture is left
addEventListener('pageshow', () => { $('send').reset(); $('pick').value = ''; say(''); });
