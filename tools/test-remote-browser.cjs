const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const dir = process.argv[2];
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
(async () => {
  for (let n = 0; !fs.existsSync(path.join(dir, 'ready.json')); n++) {
    if (n > 300) throw Error('Phone test harness did not start');
    await wait(200);
  }
  const { url } = JSON.parse(fs.readFileSync(path.join(dir, 'ready.json')));
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage({viewport:{width:1440,height:1000}});
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', d => d.accept());
  try {
    await page.goto(url);
    await page.locator('#desk').waitFor();
    await page.waitForFunction(() => document.getElementById('connection').textContent.includes('Connected'));
    assert.equal(await page.locator('#pairPanel, #pairCode').count(),0);
    const second = await browser.newContext();
    assert.equal((await second.request.get(url+'/api/state')).status(),200);
    await second.close();
    fs.writeFileSync(path.join(dir,'inject'),'synthetic OCR observation');
    await page.locator('#candidates h3').filter({hasText:'Synthetic Card'}).waitFor({timeout:5000});
    await page.locator('#search').fill('Synthetic');
    await page.locator('#searchForm button').click();
    await page.locator('#catalogResults button').filter({hasText:'Review this match'}).click();
    const confirm = page.locator('#selection button').filter({hasText:'Confirm new physical copy'});
    await confirm.waitFor();
    // Simulate response loss AFTER the real HTTP command commits. The browser must resolve
    // the persisted acknowledgment without resending a new confirmation.
    let dropped = false;
    await page.route('**/api/command', async route => {
      const body = route.request().postDataJSON();
      if (body.action === 'confirm' && !dropped) { dropped = true; await route.fetch(); await route.abort('failed'); }
      else await route.continue();
    });
    await confirm.click();
    await page.locator('#uncertain').waitFor();
    await page.locator('#checkPending').click();
    await page.waitForFunction(() => document.getElementById('uncertain').hidden);
    assert.equal((await page.request.get(url+'/api/state')).status(),200);
    await page.waitForFunction(() => document.getElementById('copies').textContent === '1');
    await page.unroute('**/api/command');
    await page.locator('[data-view=collection]').click();
    await page.locator('#collectionResults button').filter({hasText:'+ Add one'}).click();
    await page.waitForFunction(() => document.getElementById('copies').textContent === '2');
    await page.locator('#collectionResults button').filter({hasText:'Remove one'}).click();
    await page.waitForFunction(() => document.getElementById('copies').textContent === '1');
    await page.locator('[data-view=piles]').click();
    await page.locator('#pileList').filter({hasText:'Pile 1'}).waitFor();
    await page.getByRole('button',{name:'View contents',exact:true}).click();
    await page.locator('#pileContents').filter({hasText:'Synthetic Card'}).waitFor();
    await page.locator('[data-view=progress]').click();
    await page.locator('#progressResults button').first().click();
    await page.locator('#groupDetail').filter({hasText:'source-listed copies'}).waitFor();
    await page.locator('[data-view=health]').click();
    await page.locator('#refresh').waitFor();
    // A camera command is accepted by the browser UI but correctly reports the Android permission blocker.
    await page.locator('[data-view=scan]').click();
    await page.getByRole('button',{name:'Start scanning',exact:true}).click();
    await page.locator('#failure').filter({hasText:'permission'}).waitFor();
    await page.locator('[data-view=health]').click();
    await page.locator('#errors').filter({hasText:'permission'}).waitFor();
    const downloadPromise = page.waitForEvent('download');
    await page.getByRole('link',{name:'Download diagnostics',exact:true}).click();
    const download = await downloadPromise;
    const file = await download.path();
    const diagnostics = JSON.parse(fs.readFileSync(file,'utf8'));
    assert.equal(diagnostics.appVersion,'0.5.1-alpha');
    await page.screenshot({path:'/tmp/arkham-remote-browser.png',fullPage:true});
    assert.deepEqual(errors,[]);
    const final = await (await page.request.get(url+'/api/state')).json();
    assert.equal(final.totalCopies,1);
    console.log('Desktop Chromium integration passed: direct HTTP access, SSE, selection, lost acknowledgment recovery, +/- corrections, piles, progress, permission errors and diagnostics.');
    fs.writeFileSync(path.join(dir,'done'),'pass');
  } catch (e) {
    await page.screenshot({path:'/tmp/arkham-remote-browser-failure.png',fullPage:true}).catch(()=>{});
    fs.writeFileSync(path.join(dir,'done'),'fail');
    throw e;
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode=1; });
