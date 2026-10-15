const TAGS = [
  'cat', 'dog', 'landscape', 'sunset', 'portrait', 'city', 'car',
  'flower', 'mountain', 'ocean', 'cyberpunk', 'minimal', 'cinematic',
  'dramatic', 'bright', 'dark', 'realistic', 'abstract', 'night', 'day',
];

const SUBJECTS = ['cat', 'dog', 'portrait', 'city', 'car', 'flower', 'mountain', 'ocean', 'landscape'];
const ADJECTIVES = ['realistic', 'abstract', 'minimal', 'cinematic', 'dramatic', 'bright', 'dark', 'cyberpunk'];
const STYLES = ['realistic', 'abstract', 'minimal', 'cinematic', 'dramatic', 'cyberpunk'];
const PLACES = ['mountain', 'ocean', 'city', 'forest', 'desert', 'beach', 'street', 'rooftop'];
const TIMES = ['sunset', 'night', 'day', 'dawn', 'dusk'];

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

function pickDistinct(arr, used) {
  let v = pick(arr);
  let guard = 0;
  while (used.includes(v) && guard < 50) {
    v = pick(arr);
    guard++;
  }
  return v;
}

function article(word) {
  return /^[aeiou]/i.test(word) ? 'an' : 'a';
}

export function randomPrompt() {
  const used = [];

  const adj = pickDistinct(ADJECTIVES, used);
  used.push(adj);

  const subject = pickDistinct(SUBJECTS, used);
  used.push(subject);

  const style = pickDistinct(STYLES, used);
  used.push(style);

  let out = `${article(adj)} ${adj} ${subject}`;

  if (Math.random() < 0.7) {
    const place = pickDistinct(PLACES, used);
    used.push(place);
    out += ` on a ${place}`;
  }

  if (Math.random() < 0.7) {
    const time = pickDistinct(TIMES, used);
    used.push(time);
    out += ` at ${time}`;
  }

  out += `, ${style}`;
  return out;
}

export { TAGS };