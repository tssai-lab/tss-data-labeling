// Playwright CLI run-code --filename；只连接 read-errors-harness 的本地隔离页面。
async (page) => {
  await page.goto('http://127.0.0.1:18893/');
  const checks = [];
  const assert = (ok, name) => { if (!ok) throw new Error(name); checks.push(name); };
  const codes = page.locator('#code-list');
  const rows = () => codes.locator('tbody tr.ant-table-row');
  const keyword = codes.getByRole('textbox', { name: '代码名称 :', exact: true });
  const query = () => codes.getByRole('button', { name: '查 询', exact: true }).click();
  const mode = code => page.evaluate(code => { window.__qa.code = code; }, code);
  const expectPage = async (total, current, count) => {
    await page.waitForFunction(({ total, current, count }) => {
      const root = document.querySelector('#code-list');
      const totalLabel = root?.querySelector('.ant-pagination-total-text')?.textContent;
      // Antd 空清单会隐藏分页栏，不要求不存在的“总共 0 条”标签。
      const matchesTotal = (total === 0 && !totalLabel) || totalLabel?.includes('总共 ' + total + ' 条');
      return matchesTotal
        && root.querySelectorAll('tbody tr.ant-table-row').length === count
        && (!current || root.querySelector('.ant-pagination-item-active')?.textContent === String(current));
    }, { total, current, count }, { timeout: 10000 });
  };
  const rowIds = () => rows().evaluateAll(items => items.map(item => item.getAttribute('data-row-key')));
  await page.locator('#metrics').getByRole('switch').click();
  await mode('many');
  await query();
  await expectPage(23, 1, 10);
  assert(true, '23 条记录显示第一页 10 条、总数 23');

  await codes.locator('.ant-pagination-item-3').click();
  await expectPage(23, 3, 3);
  assert(true, '末页显示剩余 3 条');
  await keyword.fill('  mInIrBt  ');
  await query();
  await expectPage(12, 1, 10);
  assert(true, '从第三页筛选后回到第一页，显示匹配总数 12');
  const first = await rowIds();
  assert((await rows().allTextContents()).every(text => text.includes('MiniRBT')), '大小写及前后空格匹配正确');
  await codes.locator('.ant-pagination-item-2').click();
  await expectPage(12, 2, 2);
  const allMatches = first.concat(await rowIds());
  assert(new Set(allMatches).size === 12, '匹配结果跨两页不重复、不漏项');
  await codes.locator('.ant-pagination-prev button').click();
  await expectPage(12, 1, 10);
  await codes.locator('.ant-pagination-next button').click();
  await expectPage(12, 2, 2);
  assert(true, '上一页和下一页按钮均正确切换');
  await codes.locator('.ant-pagination-item-1').click();
  await expectPage(12, 1, 10);
  await codes.locator('.ant-pagination-item-2').click();
  await expectPage(12, 2, 2);
  assert(true, '第一页和第二页数字入口均可切换');
  const callsBeforeRefresh = await page.evaluate(() => window.__qa.calls.length);
  await codes.getByRole('img', { name: 'reload', exact: true }).click();
  await page.waitForFunction(before => window.__qa.calls.length > before, callsBeforeRefresh, { timeout: 10000 });
  await expectPage(12, 2, 2);
  assert(true, '工具栏刷新保留筛选条件、第二页及总数');
  await page.screenshot({ path: 'output/playwright/code-filter-page2.png', fullPage: true });

  await keyword.fill('  TRAIN-PAGE-15.ZIP  ');
  await query();
  await expectPage(1, 1, 1);
  assert((await rows().innerText()).includes('YOLO 15'), '按文件名筛选且总数变成 1');
  await codes.getByRole('button').filter({ has: page.getByRole('img', { name: 'close-circle', exact: true }) }).click();
  await query();
  await expectPage(23, 1, 10);
  assert(await keyword.inputValue() === '', '搜索输入框清除按钮后查询恢复总数');
  await keyword.fill('不存在的名称');
  await query();
  await expectPage(0, 0, 0);
  assert(await codes.getByRole('alert').count() === 0, '零匹配显示空列表且不误报读取失败');
  assert(await codes.locator('.ant-pagination-item-2').count() === 0, '零匹配不残留第二页入口');
  await keyword.fill('   ');
  await query();
  await expectPage(23, 1, 10);
  assert(true, '空白查询恢复完整 23 条');

  await mode('many-partial');
  await keyword.fill('MiniRBT');
  await query();
  await expectPage(11, 1, 10);
  await codes.getByText(/23 个代码资产中有 1 个版本列表加载失败/).waitFor();
  assert(true, '部分失败总数为已读取的 11 条匹配记录并保留不完整告警');
  await mode('error');
  await codes.getByRole('button', { name: '重试加载', exact: true }).click();
  await codes.getByText(/本次查询未成功/).waitFor();
  await expectPage(11, 1, 10);
  assert(true, '请求失败保留上次表格和总数并明确提示');
  await mode('many');
  await codes.getByRole('button', { name: '重试加载', exact: true }).click();
  await expectPage(12, 1, 10);
  assert(await codes.getByRole('alert').count() === 0, '重试恢复匹配总数 12 并清除告警');
  await codes.getByRole('button', { name: '重 置', exact: true }).click();
  await expectPage(23, 1, 10);
  assert(await keyword.inputValue() === '', '重置清空输入、恢复总数和第一页');
  await codes.getByRole('columnheader', { name: '文件名', exact: true }).click();
  await expectPage(23, 1, 10);
  assert(true, '相邻列排序不改变总数和每页条数');
  await mode('empty');
  await query();
  await expectPage(0, 0, 0);
  assert(await codes.getByRole('alert').count() === 0, '真实空清单不被当成加载失败');
  return { passed: checks.length, checks };
}
