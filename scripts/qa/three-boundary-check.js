// Playwright CLI 回归：仅真实主详情、读取失败/重试与路由生命周期。
async (page) => {
  page.setDefaultTimeout(10000);
  page.setDefaultNavigationTimeout(20000);
  const checks=[];const assert=(ok,label)=>{if(!ok)throw new Error(label);checks.push(label);};
  const mode=(kind,value)=>page.evaluate(([k,v])=>{window.__qa.modes[k]=v;},[kind,value]);
  const label=kind=>kind==='model'?'模型':'数据集';
  for(const kind of ['model','dataset']){
    await page.goto('http://127.0.0.1:18895/?view='+kind);
    await page.getByText(label(kind)+' a',{exact:true}).first().waitFor();
    const writeName=kind==='model'?/编辑资产/:/删除数据集/;
    assert(await page.getByRole('button',{name:writeName}).count()===1,kind+' 正常详情有原操作入口');
    await mode(kind,'held');await page.getByRole('button',{name:'测试切换 B',exact:true}).click();
    await page.waitForFunction(()=>window.__qa.held.some(x=>x.id==='b'));
    assert(await page.getByRole('button',{name:writeName}).count()===0,kind+' 加载中不显示旧写入口');
    await mode(kind,'success');await page.getByRole('button',{name:'测试切换 A',exact:true}).click();
    await page.getByText(label(kind)+' a',{exact:true}).first().waitFor();
    await page.evaluate(()=>window.__qa.release());
    await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
    assert(await page.getByText(label(kind)+' b',{exact:true}).count()===0,kind+' 旧成功不覆盖当前资产');
    await mode(kind,'error');await page.getByRole('button',{name:'测试切换 B',exact:true}).click();
    await page.getByText(label(kind)+'详情读取失败',{exact:true}).waitFor();
    assert(await page.getByRole('button',{name:'重试详情',exact:true}).count()===1,kind+' 失败有持续重试入口');
    assert(await page.getByRole('button',{name:writeName}).count()===0,kind+' 失败不暴露旧详情操作');
    await mode(kind,'success');await page.getByRole('button',{name:'重试详情',exact:true}).click();
    await page.getByText(label(kind)+' b',{exact:true}).first().waitFor();assert(true,kind+' 重试恢复当前资产');
    await mode(kind,'held');await page.getByRole('button',{name:'测试切换 A',exact:true}).click();await page.waitForFunction(()=>window.__qa.held.some(x=>x.id==='a'));
    await page.getByRole('button',{name:'测试挂载切换',exact:true}).click();await page.evaluate(()=>window.__qa.release(true));
    await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
    assert(await page.getByRole('button',{name:'重试详情',exact:true}).count()===0,kind+' 卸载后旧错误不打开错误页');
    assert(await page.evaluate(()=>window.__qa.writes)===0,kind+' 全程无业务写请求');
  }
  for(const failure of ['error','bare404','notfound','forbidden','invalid']){
    await page.goto('http://127.0.0.1:18895/?view=dataset');await page.getByText('数据集 a',{exact:true}).first().waitFor();
    await mode('workspace',failure);await page.getByRole('button',{name:'测试切换 B',exact:true}).click();
    await page.getByText('工作区读取未完成',{exact:true}).waitFor();
    assert(await page.getByText('本次读取未执行发布、放弃或删除。',{exact:false}).count()===1,'工作区 '+failure+' 明确提示而非无草稿');
    assert(await page.getByRole('button',{name:'上传新版本',exact:true}).count()===0,'工作区 '+failure+' 错误期间无编辑入口');
    await mode('workspace','success');await page.getByRole('button',{name:'重试详情',exact:true}).click();
    await page.getByText('数据集 b',{exact:true}).first().waitFor();assert(true,'工作区 '+failure+' 可恢复');
    assert(await page.evaluate(()=>window.__qa.writes)===0,'工作区 '+failure+' 重试无创建/放弃/删除');
  }
  return {passed:checks.length,checks};
}
