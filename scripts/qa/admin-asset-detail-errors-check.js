// Playwright CLI 函数表达式；只连接本地夹具，不使用真实身份或资产。
async (page) => {
  await page.goto('http://127.0.0.1:18893/?view=assets');
  const checks = [];
  const assert = (ok, name) => { if (!ok) throw new Error(name); checks.push(name); };
  const mode = value => page.evaluate(value => { window.__qa.assetMode = value; }, value);
  const manage = letter => page.getByRole('row').filter({ has: page.getByRole('cell', { name: `测试资产 ${letter}`, exact: true }) }).getByRole('button', { name: '管理', exact: true }).click();
  const back = () => page.getByRole('button', { name: 'back', exact: true }).click();
  const profile = () => page.getByRole('textbox', { name: 'trainingProfile', exact: true });
  const ready = async letter => {
    await page.getByRole('button', { name: '保存元数据', exact: true }).waitFor({ timeout: 10000 });
    await page.waitForFunction(letter => document.querySelector('input[id="name"]')?.value === `测试资产 ${letter}`, letter, { timeout: 10000 });
  };
  const release = () => page.evaluate(async () => {
    window.__qa.release();
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  });
  await manage('A');
  await ready('A');
  assert(await profile().isDisabled(), '成功读取已有版本，方案字段仍锁定');
  assert(await page.getByRole('button', { name: '查看文件', exact: true }).count() === 2, '完整展示两个版本');
  await page.getByText('只读文件 README.md · v2-asset-a', { exact: true }).waitFor();
  assert(true, '默认只读预览最新版本，不自动打开工作区');
  await page.getByRole('button', { name: '查看文件', exact: true }).first().click();
  await page.getByText('只读文件 README.md · v1-asset-a', { exact: true }).waitFor();
  await page.getByRole('treeitem', { name: 'train.py', exact: true }).click();
  await page.getByText('只读文件 train.py · v1-asset-a', { exact: true }).waitFor();
  await page.getByRole('treeitem', { name: 'README.md', exact: true }).click();
  await page.getByText('只读文件 README.md · v1-asset-a', { exact: true }).waitFor();
  assert(true, '版本一查看入口及两个文件选择均正确只读切换');
  await page.getByRole('button', { name: '查看文件', exact: true }).last().click();
  await page.getByText('只读文件 README.md · v2-asset-a', { exact: true }).waitFor();
  assert(true, '版本二的独立查看入口仍正常');
  await back();
  await manage('B');
  await ready('B');
  await page.getByText('只读文件 README.md · v2-asset-b', { exact: true }).waitFor();
  assert(true, '返回列表并管理 B 不残留 A 的表单或版本预览');

  for (const value of ['versions-error', 'detail-error', 'versions-invalid', 'detail-invalid']) {
    await back();
    await mode(value);
    await manage('A');
    await page.getByRole('button', { name: '重试详情', exact: true }).waitFor();
    assert(await page.getByRole('button', { name: '保存元数据', exact: true }).count() === 0
      && await page.getByRole('button', { name: '删除资产', exact: true }).count() === 0
      && await profile().count() === 0, `${value}：不展示可操作旧详情`);
    assert(await page.getByText('暂无版本', { exact: true }).count() === 0, `${value}：未把失败宣称为没有版本`);
  }
  await page.screenshot({ path: 'output/playwright/admin-asset-detail-error.png', fullPage: true, animations: 'disabled' });
  await mode('held');
  await page.getByRole('button', { name: '重试详情', exact: true }).click();
  await page.getByText('正在读取资产详情与版本', { exact: true }).waitFor();
  await page.waitForFunction(() => window.__qa.held.length === 2);
  assert(await page.getByRole('button', { name: '保存元数据', exact: true }).count() === 0
    && await page.getByRole('button', { name: '重试详情', exact: true }).count() === 0, '慢速重试期间不显示编辑或重复重试入口');
  await release();
  await ready('A');
  assert(await profile().isDisabled() && await page.getByRole('button', { name: '重试详情', exact: true }).count() === 0, '重试成功后清除错误并恢复锁定详情');
  await page.getByText('只读文件 README.md · v2-asset-a', { exact: true }).waitFor();
  assert(true, '首次详情重试成功后恢复最新版本预览');

  await back();
  await mode('empty');
  await manage('B');
  await ready('B');
  await page.getByText('暂无版本', { exact: true }).waitFor();
  assert(await profile().isEnabled(), '真实零版本才显示空态并允许原有方案设置');
  await profile().fill('nlp');
  assert(await profile().inputValue() === 'nlp', '零版本方案输入仍可编辑（未保存）');
  await page.screenshot({ path: 'output/playwright/admin-asset-detail-empty.png', fullPage: true, animations: 'disabled' });

  await back();
  await mode('held');
  await manage('A');
  await page.waitForFunction(() => window.__qa.held.length === 2);
  await back();
  await mode('success');
  await manage('B');
  await ready('B');
  await release();
  assert(await page.getByRole('textbox', { name: '* 名称', exact: true }).inputValue() === '测试资产 B', '退出慢请求 A 后打开 B，迟到 A 不覆盖新详情');
  await page.getByText('只读文件 README.md · v2-asset-b', { exact: true }).waitFor();
  assert(true, '迟到 A 也不重新发起其版本预览');

  await back();
  await mode('held');
  await manage('A');
  await page.waitForFunction(() => window.__qa.held.length === 2);
  const before = await page.evaluate(() => window.__qa.assetCalls.length);
  await page.getByRole('button', { name: '卸载测试页', exact: true }).click();
  await release();
  assert(await page.getByRole('button', { name: '保存元数据', exact: true }).count() === 0
    && await page.evaluate(() => window.__qa.assetCalls.length) === before, '卸载后迟到元数据不再提交页面状态或启动文件读取');
  await mode('success');
  await page.getByRole('button', { name: '挂载测试页', exact: true }).click();
  await manage('A');
  await ready('A');
  assert(await profile().isDisabled(), '重新挂载后仍正常读取且保持方案锁定');
  const calls = await page.evaluate(() => window.__qa.assetCalls);
  assert(calls.every(call => call.method === 'GET') && !calls.some(call => call.url.includes('/workspaces')), '本轮详情与重试只有 GET，不打开工作区或提交写请求');
  return { passed: checks.length, checks, scope: '管理员资产详情局部隔离回归，不是业务写入或线上权限验收' };
}
