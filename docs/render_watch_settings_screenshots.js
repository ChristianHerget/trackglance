const path = require('path');
const { chromium } = require('playwright-chromium');
const settings = require('../watchapp/src/pkjs/index.js');

async function main() {
  const outputDirectory = process.argv[2];
  if (!outputDirectory) {
    throw new Error('Output directory is required');
  }

  const configuration = JSON.parse(JSON.stringify(settings.defaults));
  configuration.watchHrToLocus = true;
  configuration.heartRateIntervalSeconds = 5;
  settings.add(configuration, {
    name: 'Hiking',
    locus: 'Hiking',
    metrics: [1, 3, 5, 10, 11, 22],
  });

  const settingsUrl = settings.page(
    configuration,
    ['Walking', 'Cycling', 'Hiking'],
    'en',
    'fresh',
    true,
    null,
  );
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 2,
  });

  await page.goto(settingsUrl);
  await page.screenshot({
    path: path.join(outputDirectory, 'watch_settings_overview.png'),
  });

  await page.getByRole('button', { name: 'Edit' }).click();
  await page.screenshot({
    path: path.join(outputDirectory, 'watch_settings_profile.png'),
  });

  await browser.close();
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
