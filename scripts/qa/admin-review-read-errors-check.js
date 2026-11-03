// Playwright CLI 函数表达式：不能在末尾加分号。仅验证本地隔离列表，不操作审批。
async (page) => {
  if (!page.url().startsWith('http://127.0.0.1:18893/')) throw new Error('仅允许本地夹具');
  const checks = [];
  const assert = (value, name) => { if (!value) throw new Error(name); checks.push(name); };
  const rows = page.locator('tbody tr.ant-table-row');
  const error = page.locator('.ant-alert-error');
  const warning = page.locator('.ant-alert-warning');
  const keyword = page.getByRole('textbox', { name: '关键词 :', exact: true });
  const query = () => page.getByRole('button', { name: '查 询', exact: true }).click();
  const refresh = () => page.getByRole('button', { name: '刷 新', exact: true }).click();
  const mode = value => page.evaluate(value => { window.__qa.adminMode = value; }, value);
  const expectRows = async count => {
    try { await page.waitForFunction(count => document.querySelectorAll('tbody tr.ant-table-row').length === count, count, { timeout: 10000 }); }
    catch { throw new Error(`预期 ${count} 行，实际 ${await rows.count()} 行；已通过 ${JSON.stringify(checks)}`); }
  };
  const expectTotal = async total => {
    try { await page.waitForFunction(total => document.querySelector('.ant-pagination-total-text')?.textContent.includes(`总共 ${total} 条`), total, { timeout: 10000 }); }
    catch { throw new Error(`总数应为 ${total}；实际 ${await page.locator('.ant-pagination-total-text').allTextContents()}；已通过 ${checks.length} 项`); }
  };
  const select = async (title, label) => {
    await page.locator('.ant-form-item').filter({ has: page.locator(`label[title="${title}"]`) }).locator('.ant-select-selector').click();
    await page.locator('.ant-select-item-option-content').getByText(label, { exact: true }).click();
  };
  await page.evaluate(() => localStorage.removeItem('tssai.pendingCodeVersions'));
  await page.goto('http://127.0.0.1:18893/?view=admin');
  await expectRows(10);
  await expectTotal(13);
  assert(await error.count() === 0 && await warning.count() === 0, '正常读取 13 条，第一页 10 条，无错误告警');
  await page.locator('.ant-pagination-item-2').click();
  await expectRows(3);
  assert((await rows.first().innerText()).includes('待审代码 10'), '第二页显示远端第二页记录');
  await page.locator('.ant-pagination-prev button').click();
  await expectRows(10);
  await page.locator('.ant-pagination-next button').click();
  await expectRows(3);
  await page.locator('.ant-pagination-item-1').click();
  await expectRows(10);
  assert(true, '上一页、下一页、第一页与第二页入口均可用');
  await page.locator('.ant-pagination-options-size-changer .ant-select-selector').click();
  await page.getByText('20 条/页', { exact: true }).click();
  await expectRows(13);
  await page.locator('.ant-pagination-options-size-changer .ant-select-selector').click();
  await page.getByText('10 条/页', { exact: true }).click();
  await expectRows(10);
  assert(true, '每页 10/20 条切换与请求分页一致');
  await keyword.fill(' train-11.py ');
  await query();
  await expectRows(1);
  await expectTotal(1);
  assert((await rows.innerText()).includes('待审代码 11'), '关键词前后空格处理后按文件名查询');
  await page.getByRole('button', { name: '重 置', exact: true }).click();
  await expectRows(10);
  await expectTotal(13);
  assert(await keyword.inputValue() === '', '重置清空关键词并恢复列表');
  const before = await page.evaluate(() => window.__qa.adminCalls.length);
  await page.getByRole('img', { name: 'reload', exact: true }).click();
  await page.waitForFunction(before => window.__qa.adminCalls.length > before, before, { timeout: 10000 });
  assert(true, '工具栏图标刷新执行新查询');

  // 只在这个隔离浏览器中准备本地登记，验证它不能掩盖接口失败。
  await page.evaluate(() => localStorage.setItem('tssai.pendingCodeVersions', JSON.stringify([{codeVersionId:'local-only',codeAssetName:'本地登记',approvalStatus:'PENDING'}])));
  await mode('primary-error');
  await refresh();
  await error.getByText('测试审核队列不可用', { exact: true }).waitFor({ timeout: 10000 });
  await expectRows(10);
  assert(await rows.filter({ hasText: '本地登记' }).count() === 0, '主列表失败保留旧表，不用本地登记替换或伪装成功');
  assert((await error.innerText()).includes('上次查询结果'), '持续告警说明旧表并非当前筛选的最新结果');
  await page.screenshot({ path: 'output/playwright/admin-review-stale.png', fullPage: true, animations: 'disabled' });
  await page.evaluate(() => localStorage.removeItem('tssai.pendingCodeVersions'));
  await mode('success');
  await page.getByRole('button', { name: '重试加载', exact: true }).click();
  await error.waitFor({ state: 'hidden', timeout: 10000 });
  await expectRows(10);
  assert(true, '重试成功后恢复队列并清除持续告警');

  await mode('partial');
  await refresh();
  await warning.getByText(/1 个资产的版本列表补查失败/).waitFor({ timeout: 10000 });
  await expectRows(1);
  assert((await rows.innerText()).includes('补查代码 A'), '补查部分失败保留成功记录并说明不完整');
  await page.screenshot({ path: 'output/playwright/admin-review-partial.png', fullPage: true, animations: 'disabled' });
  for (const value of ['fallback-error', 'versions-error', 'primary-invalid']) {
    await mode(value);
    await refresh();
    await error.waitFor({ timeout: 10000 });
    await expectRows(1);
    assert((await rows.innerText()).includes('补查代码 A'), `${value}：失败不清空上次已读记录`);
  }
  await mode('truncated');
  await page.getByRole('button', { name: '重试加载', exact: true }).click();
  await warning.getByText(/未覆盖全部范围/).waitFor({ timeout: 10000 });
  await expectRows(2);
  assert(true, '补查范围截断时不宣称完整');
  await mode('empty');
  await refresh();
  await expectRows(0);
  await warning.waitFor({ state: 'hidden', timeout: 10000 });
  assert(await error.count() === 0, '主列表和补查均成功为空时显示真实空态');
  await mode('success');
  await refresh();
  await expectRows(10);

  for (const [value, label] of [['REJECTED', '已拒绝'], ['APPROVED', '已通过'], ['REVOKED', '已撤销'], ['PENDING', '待审核']]) {
    await select('审核状态', label);
    await query();
    await expectRows(value === 'PENDING' ? 10 : 0);
    const status = await page.evaluate(() => window.__qa.adminCalls.filter(call => call.url === '/v2/admin/code-review-tasks').at(-1).params.approvalStatus);
    assert(status === value, `审核状态 ${value} 筛选透传且不改变状态`);
  }
  await page.getByText('展开', { exact: true }).click();
  for (const risk of ['HIGH', 'MEDIUM', 'UNKNOWN', 'LOW']) {
    await select('风险等级', risk);
    await query();
    await expectRows(risk === 'LOW' ? 10 : 0);
    assert(true, `风险 ${risk} 筛选正常`);
  }
  await page.getByRole('textbox', { name: '归属用户 :', exact: true }).fill('qa-owner');
  await select('排序', '版本');
  await select('排序方向', '升序（旧→新）');
  await query();
  const params = await page.evaluate(() => window.__qa.adminCalls.filter(call => call.url === '/v2/admin/code-review-tasks').at(-1).params);
  assert(params.ownerUserId === 1 && params.sortBy === 'VERSION' && params.sortDirection === 'ASC', '归属用户名和排序参数透传（不冒充后端排序验收）');
  await select('排序', '提交时间');
  await select('排序方向', '降序（新→旧）');
  await query();
  await page.getByText('收起', { exact: true }).click();
  assert(await page.getByRole('textbox', { name: '归属用户 :', exact: true }).isVisible() === false, '筛选区展开和收起正常');
  await page.getByRole('button', { name: '重 置', exact: true }).click();
  await expectRows(10);
  await expectTotal(13);

  await page.goto('http://127.0.0.1:18893/?view=admin&role=user');
  await page.waitForFunction(() => window.__qa.navigation.includes('/403'), null, { timeout: 10000 });
  assert(await page.getByRole('button', { name: '刷 新', exact: true }).count() === 0, '非管理员页面仍拒绝展示（仅前端隔离角色测试）');
  return { passed: checks.length, checks, scope: '管理员只读列表本地回归，不是审批或真实权限验收' };
}
