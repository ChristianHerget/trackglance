const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright-chromium');

const WIDTH = 200;
const HEIGHT = 228;
const REPLACED_AREAS = [
  [65, 0, 70, 20],
  [100, 67, 100, 35],
  [0, 126, 100, 35],
  [100, 126, 100, 35],
  [0, 190, 100, 38],
];

function dataUrl(filename) {
  return `data:image/png;base64,${fs.readFileSync(filename).toString('base64')}`;
}

async function compose(page, baseFilename, updateFilename) {
  const result = await page.evaluate(async ({ baseUrl, updateUrl, areas, width, height }) => {
    const load = (url) => new Promise((resolve, reject) => {
      const image = new Image();
      image.onload = () => resolve(image);
      image.onerror = reject;
      image.src = url;
    });
    const [base, update] = await Promise.all([load(baseUrl), load(updateUrl)]);
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    context.drawImage(base, 0, 0);
    context.fillStyle = '#000';
    for (const [x, y, areaWidth, areaHeight] of areas) {
      context.fillRect(x, y, areaWidth, areaHeight);
    }

    const updateCanvas = document.createElement('canvas');
    updateCanvas.width = width;
    updateCanvas.height = height;
    const updateContext = updateCanvas.getContext('2d');
    updateContext.drawImage(update, 0, 0);
    const destination = context.getImageData(0, 0, width, height);
    const source = updateContext.getImageData(0, 0, width, height);
    for (let offset = 0; offset < source.data.length; offset += 4) {
      if (source.data[offset] || source.data[offset + 1] || source.data[offset + 2]) {
        destination.data[offset] = source.data[offset];
        destination.data[offset + 1] = source.data[offset + 1];
        destination.data[offset + 2] = source.data[offset + 2];
        destination.data[offset + 3] = 255;
      }
    }
    context.putImageData(destination, 0, 0);
    return canvas.toDataURL('image/png').split(',')[1];
  }, {
    baseUrl: dataUrl(baseFilename),
    updateUrl: dataUrl(updateFilename),
    areas: REPLACED_AREAS,
    width: WIDTH,
    height: HEIGHT,
  });
  fs.writeFileSync(updateFilename, Buffer.from(result, 'base64'));
}

async function main() {
  const outputDirectory = process.argv[2];
  if (!outputDirectory) throw new Error('Output directory is required');
  // QEMU exposes the first unit-only screen update as an incremental framebuffer. The following
  // nautical capture is complete, so it supplies the unchanged labels and metrics for both earlier
  // frames while each earlier capture supplies its own changed values and status-bar time.
  const base = path.join(outputDirectory, 'screenshot_emery_units_nautical.png');
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  for (const filename of ['screenshot_emery_dashboard.png', 'screenshot_emery_units_imperial.png']) {
    await compose(page, base, path.join(outputDirectory, filename));
  }
  await browser.close();
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
