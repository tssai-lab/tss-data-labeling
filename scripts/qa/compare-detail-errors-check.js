// Playwright CLI run-code --filename；真实对比页 + 本地隔离响应，无后端写操作。
// CLI 将全文作为函数表达式执行，末尾不能添加分号（不是独立 Node 脚本）。
async (page) => {
  if (!page.url().startsWith('http://127.0.0.1:18893/'))
    throw new Error('仅允许本地夹具');
  const checks = [];
  const assert = (ok, name) => {
    if (!ok) throw new Error(name);
    checks.push(name);
  };
  const card = (title) =>
    page.locator('.ant-card').filter({
      has: page.locator('.ant-card-head-title', { hasText: title }),
    });
  const tasks = card('选择训练任务');
  const button = () => page.getByRole('button', { name: /加载对比数据/ });
  const checkbox = (id) =>
    tasks.locator(`tr[data-row-key="${id}"]`).getByRole('checkbox');
  const mode = (config) =>
    page.evaluate((config) => {
      window.__qa.compare = config;
    }, config);
  const notice = (text) =>
    page.locator('.ant-message-notice-content').filter({ hasText: text });
  const load = async () => {
    await button().click();
    await page.waitForFunction(
      () => !document.querySelector('.ant-btn-loading'),
      null,
      { timeout: 10000 },
    );
  };
  const reset = async () => {
    await page.goto('http://127.0.0.1:18893/?view=compare');
    await checkbox('a').waitFor({ timeout: 10000 });
  };
  const chooseTwo = async () => {
    await checkbox('a').check();
    await checkbox('b').check();
  };
  const expectResult = async (count) => {
    if (count === 0) {
      await card('单值指标多维对照').waitFor({
        state: 'hidden',
        timeout: 10000,
      });
    } else {
      await page.waitForFunction(
        (count) => {
          const head = [
            ...document.querySelectorAll('.ant-card-head-title'),
          ].find((e) => e.textContent === '单值指标多维对照');
          return (
            head
              ?.closest('.ant-card')
              .querySelectorAll('tbody tr.ant-table-row').length === count
          );
        },
        count,
        { timeout: 10000 },
      );
    }
  };

  await reset();
  assert(await button().isDisabled(), '未选择时禁止加载');
  await checkbox('a').check();
  assert(await button().isDisabled(), '只选一项时禁止加载');
  await checkbox('b').check();
  assert(await button().isEnabled(), '选择两项后可加载');
  await load();
  await expectResult(2);
  assert(
    await card('单值指标多维对照')
      .getByText('0.8000', { exact: true })
      .isVisible(),
    '成功读取后展示真实样本值及两项结果',
  );
  await page.waitForFunction(
    () => document.querySelectorAll('canvas').length >= 3,
    null,
    { timeout: 10000 },
  );
  assert(true, '性能提升与过程曲线均正常渲染');
  await card('性能提升曲线').locator('.ant-select-selector').click();
  await page
    .locator('.ant-select-item-option-content')
    .getByText('验证准确率', { exact: true })
    .click();
  assert(
    await card('性能提升曲线')
      .getByText('验证准确率（各版本最终值）', { exact: true })
      .isVisible(),
    '指标下拉选择可用',
  );
  await page.getByRole('checkbox', { name: '训练损失', exact: true }).uncheck();
  await card('过程曲线图').waitFor({ state: 'hidden', timeout: 10000 });
  await page.getByRole('checkbox', { name: '训练损失', exact: true }).check();
  await card('过程曲线图').waitFor({ timeout: 10000 });
  assert(true, '过程指标取消和恢复勾选正常');

  await mode({ a: 'error', b: 'error' });
  await load();
  await notice(/2 个任务详情加载失败/).waitFor({ timeout: 10000 });
  await expectResult(0);
  assert(true, '全部详情失败明确报错，按原规则清空旧对比');
  await page.screenshot({
    path: 'output/playwright/compare-detail-failure.png',
    fullPage: true,
    animations: 'disabled',
  });
  await notice(/2 个任务详情加载失败/).waitFor({
    state: 'hidden',
    timeout: 10000,
  });
  await mode({});
  await load();
  await expectResult(2);
  assert(
    (await page.locator('.ant-message-error').count()) === 0,
    '原按钮重试成功且不沿用上轮失败计数',
  );

  await reset();
  await chooseTwo();
  await mode({ b: 'error' });
  await load();
  await notice(/1 个任务详情加载失败.*不足以形成有效对比/).waitFor({
    timeout: 10000,
  });
  await expectResult(0);
  assert(true, '一项详情失败一项成功，提示失败原因而非单纯无指标');

  await reset();
  await tasks
    .getByRole('checkbox', { name: 'Select all', exact: true })
    .check();
  await mode({ c: 'error' });
  await load();
  await notice(/已加载 2 个任务.*1 个任务详情加载失败/).waitFor({
    timeout: 10000,
  });
  await expectResult(2);
  assert(true, '全选三项、两项成功一项详情失败，保留有效对比并警告');
  await page.screenshot({
    path: 'output/playwright/compare-detail-partial.png',
    fullPage: true,
    animations: 'disabled',
  });
  await checkbox('c').uncheck();
  assert((await checkbox('c').isChecked()) === false, '单行取消选择正常');
  await tasks
    .getByRole('checkbox', { name: 'Select all', exact: true })
    .check();
  await tasks
    .getByRole('checkbox', { name: 'Select all', exact: true })
    .uncheck();
  assert(await button().isDisabled(), '取消全选后禁止重新加载');

  for (const [config, text, label] of [
    [
      { a: 'no-run', b: 'no-run' },
      /尚无运行记录（Run ID）/,
      '有效详情但没有 Run ID 时才提示没有运行记录',
    ],
    [
      { a: 'error', b: 'no-run' },
      /任务详情加载失败/,
      '详情失败和无 Run ID 混合时不掩盖读取错误',
    ],
    [
      { a: 'invalid', b: 'invalid' },
      /2 个任务详情加载失败/,
      '空对象不被补成无指标任务',
    ],
    [
      { a: 'business-error', b: 'business-error' },
      /2 个任务详情加载失败/,
      'HTTP 成功但业务失败仍报详情错误',
    ],
    [
      { a: 'error', b: 'metrics-error' },
      /任务详情加载失败.*训练指标加载失败/,
      '详情失败与指标失败同时存在时分别说明',
    ],
    [
      { a: 'empty', b: 'empty' },
      /没有共同核心指标/,
      '成功读取到空指标保留原判断',
    ],
  ]) {
    await reset();
    await chooseTwo();
    await mode(config);
    await load();
    await notice(text).waitFor({ timeout: 10000 });
    await expectResult(0);
    assert(true, label);
    if (
      Object.values(config).every((value) =>
        ['no-run', 'invalid', 'business-error'].includes(value),
      )
    ) {
      assert(
        await page.evaluate(
          () => !window.__qa.calls.some((url) => url.startsWith('/qa-metrics')),
        ),
        `${label}：不请求无效 Run`,
      );
    }
  }

  await reset();
  await chooseTwo();
  await mode({ a: 'held' });
  await button().click();
  await page.waitForFunction(() => window.__qa.held.length === 1, null, {
    timeout: 10000,
  });
  assert(
    (await button().getAttribute('class')).includes('ant-btn-loading'),
    '详情延迟期间显示加载状态',
  );
  await button().click();
  assert(
    await page.evaluate(() => window.__qa.detailCalls.length === 1),
    '加载期间重复点击不发起第二轮请求',
  );
  await page.evaluate(() => window.__qa.release());
  await expectResult(2);
  assert(true, '延迟详情返回后正常完成对比');

  await page.goto('http://127.0.0.1:18893/?view=compare&ids=a,a');
  await notice(/至少选择 2 个训练任务/).waitFor({ timeout: 10000 });
  assert(
    await page.evaluate(() => window.__qa.detailCalls.length === 0),
    '重复 URL 编号不能凑成两项并触发指标查询',
  );
  return {
    passed: checks.length,
    checks,
    scope: '本地隔离页面，不代表线上验收',
  };
}
