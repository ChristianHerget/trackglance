'use strict';

const assert = require('assert');
const {chromium} = require('playwright-chromium');
const settings = require('../watchapp/src/pkjs/index.js');

const catalog = [{id:'1', name:'Hiking'}];

function settingsUrl() {
  const configuration = settings.reconcile(settings.defaultsFor('en'), catalog, 'en').config;
  const activity = settings.activity(configuration, '1');
  activity.pages[0].metrics = [1,2];
  activity.pages[1].metrics = [3];
  activity.pages[2].metrics = [4];
  activity.pages[3].metrics = [];
  return settings.settingsPage(configuration, catalog, 'en', 'fresh', null);
}

async function openEditor(browser) {
  const context = await browser.newContext({
    viewport: {width:390, height:844},
    deviceScaleFactor: 2,
    hasTouch: true,
    isMobile: true,
  });
  const page = await context.newPage();
  await page.goto(settingsUrl());
  await page.getByRole('button', {name:'Edit activity Hiking'}).click();
  return {context, page};
}

async function touchDrag(context, locator, target) {
  await locator.scrollIntoViewIfNeeded();
  const box = await locator.boundingBox();
  assert(box, 'drag source must be visible');
  const start = {x:box.x + box.width / 2, y:box.y + box.height / 2};
  const client = await context.newCDPSession(context.pages()[0]);
  await client.send('Input.dispatchTouchEvent', {
    type:'touchStart',
    touchPoints:[{...start, radiusX:4, radiusY:4, force:1, id:1}],
  });
  for (let step = 1; step <= 6; step += 1) {
    const x = start.x + (target.x - start.x) * step / 6;
    const y = start.y + (target.y - start.y) * step / 6;
    await client.send('Input.dispatchTouchEvent', {
      type:'touchMove',
      touchPoints:[{x, y, radiusX:4, radiusY:4, force:1, id:1}],
    });
  }
  await client.send('Input.dispatchTouchEvent', {type:'touchEnd', touchPoints:[]});
}

async function metrics(page) {
  return page.evaluate(() => draft.pages.map(item => item.metrics.slice()));
}

async function assertClean(page) {
  assert.strictEqual(await page.locator('.drag-floating,.drag-placeholder,.drop-target,.drop-unavailable').count(), 0);
}

async function main() {
  const browser = await chromium.launch({headless:true});
  try {
    {
      const {context, page} = await openEditor(browser);
      const firstPage = page.locator('.page').nth(0);
      const secondRow = firstPage.locator('.metric').nth(1);
      const target = await secondRow.boundingBox();
      assert(target);
      await touchDrag(context, firstPage.locator('.metric .handle').nth(0), {
        x:target.x + target.width / 2,
        y:target.y + target.height - 2,
      });
      assert.deepStrictEqual((await metrics(page))[0], [2,1]);
      await assertClean(page);
      await context.close();
    }
    {
      const {context, page} = await openEditor(browser);
      const destination = page.locator('.page').nth(1).locator('.add');
      await destination.scrollIntoViewIfNeeded();
      const target = await destination.boundingBox();
      assert(target);
      await touchDrag(context, page.locator('.page').nth(0).locator('.metric .handle').nth(0), {
        x:target.x + target.width / 2,
        y:target.y - 4,
      });
      assert.deepStrictEqual(await metrics(page), [[2],[3,1],[4],[]]);
      await assertClean(page);
      await context.close();
    }
    {
      const {context, page} = await openEditor(browser);
      const secondPage = page.locator('.page').nth(1);
      await secondPage.scrollIntoViewIfNeeded();
      const target = await secondPage.boundingBox();
      assert(target);
      const idsBefore = await page.evaluate(() => draft.pages.map(item => item.id));
      await touchDrag(context, page.locator('.handle[data-key="page"]').nth(0), {
        x:target.x + target.width / 2,
        y:target.y + target.height - 2,
      });
      const idsAfter = await page.evaluate(() => draft.pages.map(item => item.id));
      assert.strictEqual(idsAfter[1], idsBefore[0]);
      await assertClean(page);
      await context.close();
    }
    {
      const {context, page} = await openEditor(browser);
      const handle = page.locator('.page').nth(0).locator('.metric .handle').nth(0);
      await handle.tap();
      assert.strictEqual(await page.locator('.move-menu').count(), 1);
      assert.strictEqual(await page.locator('.drag-floating').count(), 0,
        'a tap opens the fallback without selecting a floating row');
      await context.close();
    }
  } finally {
    await browser.close();
  }
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
