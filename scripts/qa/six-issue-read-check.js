// Playwright CLI run-code：只操作隔离回执，不发起真实业务请求。
async (page) => {
  const checks=[];
  const assert=(ok,label)=>{if(!ok)throw new Error(label);checks.push(label);};
  const mode=value=>page.evaluate(value=>{window.__qa.mode=value;},value);
  const settle=()=>page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
  await page.goto('http://127.0.0.1:18894/');
  await page.locator('#history-data').filter({hasText:'version-a'}).waitFor();
  await mode('error'); await page.getByRole('button',{name:'刷新历史',exact:true}).click();
  await page.locator('#history-error').filter({hasText:'历史服务故障'}).waitFor();
  assert(await page.locator('#history-data').textContent()==='version-a','历史失败保留同一实验上次结果并显示错误');
  await mode('success'); await page.getByRole('button',{name:'刷新历史',exact:true}).click(); await settle();
  assert(await page.locator('#history-error').textContent()==='','历史重试成功清除错误');
  await mode('held'); await page.getByRole('button',{name:'刷新历史',exact:true}).click();
  await page.waitForFunction(()=>window.__qa.held.length>0);
  await mode('success'); await page.getByRole('button',{name:'实验 B',exact:true}).click();
  await page.locator('#history-data').filter({hasText:'version-b'}).waitFor();
  await page.evaluate(()=>window.__qa.release()); await settle();
  assert(await page.locator('#history-data').textContent()==='version-b','迟到的实验 A 不覆盖实验 B');
  await mode('invalid'); await page.getByRole('button',{name:'刷新历史',exact:true}).click();
  await page.locator('#history-error').filter({hasText:'回执不完整'}).waitFor();assert(true,'畸形历史回执不冒充空列表');
  await mode('empty'); await page.getByRole('button',{name:'刷新历史',exact:true}).click();await settle();
  assert(await page.locator('#history-data').textContent()==='' && await page.locator('#history-error').textContent()==='','合法空历史与错误区分');
  await page.goto('http://127.0.0.1:18894/?view=readme&mode=error');
  await page.getByRole('button',{name:'重试读取',exact:true}).waitFor();
  assert(await page.getByText('README 读取失败',{exact:true}).count()===1,'README 失败展示错误而非空内容');
  await mode('success');await page.getByRole('button',{name:'重试读取',exact:true}).click();
  await page.getByText('README model-ver-a',{exact:true}).waitFor();assert(true,'README 重试恢复正文');
  await page.goto('http://127.0.0.1:18894/?view=readme&mode=empty');
  await page.getByText('当前版本未包含 README.md',{exact:true}).waitFor();assert(true,'README 真正缺失仍显示空状态');
  await page.goto('http://127.0.0.1:18894/?view=model&mode=error');
  await page.getByText('部分模型信息读取失败',{exact:true}).waitFor();
  assert(await page.getByText('测试模型',{exact:true}).count()>0,'模型元数据保留且代码与清单失败可见');
  await mode('success');await page.getByRole('alert').filter({hasText:'部分模型信息读取失败'}).getByRole('button',{name:'重试读取',exact:true}).click();
  await page.getByText('部分模型信息读取失败',{exact:true}).waitFor({state:'detached'});assert(true,'模型只读重试成功恢复');
  assert(await page.evaluate(()=>window.__qa.writes)===0,'所有重试均未调用写接口');
  return {passed:checks.length,checks};
}
