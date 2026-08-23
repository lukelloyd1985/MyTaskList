const { chromium } = require('playwright');
const path = require('path');

const jobs = [
  { file: 'icon.html', out: 'icon-512.png', width: 512, height: 512 },
  { file: 'feature-graphic.html', out: 'feature-graphic-1024x500.png', width: 1024, height: 500 },
  { file: 'screenshot-signin.html', out: 'screenshot-1-sign-in.png', width: 1080, height: 1920 },
  { file: 'screenshot-lists.html', out: 'screenshot-2-lists.png', width: 1080, height: 1920 },
  { file: 'screenshot-detail.html', out: 'screenshot-3-list-detail.png', width: 1080, height: 1920 },
];

const outDir = path.resolve(__dirname, '..');

(async () => {
  const browser = await chromium.launch({ headless: true });
  for (const job of jobs) {
    const page = await browser.newPage({ viewport: { width: job.width, height: job.height }, deviceScaleFactor: 1 });
    const filePath = 'file://' + path.resolve(__dirname, job.file);
    await page.goto(filePath);
    await page.waitForTimeout(100);
    await page.screenshot({ path: path.resolve(outDir, job.out) });
    await page.close();
    console.log('Rendered', job.out);
  }
  await browser.close();
})();
