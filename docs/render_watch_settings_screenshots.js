const path = require('path');
const { chromium } = require('playwright-chromium');
const settings = require('../watchapp/src/pkjs/index.js');

async function main() {
  const outputDirectory = process.argv[2];
  if (!outputDirectory) {
    throw new Error('Output directory is required');
  }

  const catalog = [
    {id: '2', name: 'Cycling'},
    {id: '1', name: 'Hiking'},
    {id: '3', name: 'Running'},
  ];
  const configuration = settings.reconcile(settings.defaultsFor('en'), catalog, 'en').config;
  configuration.watchHrToLocus = true;
  configuration.heartRateIntervalSeconds = 5;
  settings.add(configuration, '1', settings.activity(configuration, '1').pages[0], 'en');
  settings.activity(configuration, '1').pages[1].name = 'Climb';

  const settingsUrl = settings.settingsPage(
    configuration,
    catalog,
    'en',
    'fresh',
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

  await page.getByRole('button', { name: 'Default' }).first().click();
  await page.screenshot({
    path: path.join(outputDirectory, 'watch_settings_profile.png'),
  });

  await browser.close();
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
