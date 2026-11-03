async (page) => {
  const checks=[];const assert=(ok,label)=>{if(!ok)throw new Error(label);checks.push(label);};
  await page.goto('http://127.0.0.1:18894/?view=steps');
  await page.getByRole('button',{name:'步骤 1',exact:true}).waitFor();
  page.setDefaultTimeout(10000);
  const labels=['训练方案','基础模型权重版本','数据集版本','训练参数（JSON）','可训练硬件型号','执行方式'];
  for(let i=0;i<6;i++){
    await page.getByRole('button',{name:'步骤 '+(i+1),exact:true}).click();
    await page.getByText(labels[i],{exact:true}).first().waitFor();assert(true,'步骤 '+(i+1)+' 展示正常');
    if(i===1){await page.getByRole('button',{name:'刷新模型列表',exact:true}).click();assert(await page.evaluate(()=>window.__qa.calls.some(c=>c.includes('reload-model'))),'模型刷新仍调用原回调');}
    if(i===2){await page.getByRole('button',{name:'刷新数据集列表',exact:true}).click();assert(await page.evaluate(()=>window.__qa.calls.some(c=>c.includes('reload-dataset'))),'数据集刷新仍调用原回调');}
    if(i===3){await page.getByText('上传新包',{exact:true}).click();await page.getByLabel('代码资产名称',{exact:true}).waitFor();assert(true,'训练代码上传面板可切换');await page.getByText('选择已有',{exact:true}).click();await page.getByText('训练代码版本',{exact:true}).waitFor();assert(true,'已有代码面板可恢复');}
    if(i===4){
      await page.getByText('自定义配置',{exact:true}).click();
      await page.getByLabel('CPU 核数',{exact:true}).fill('2');await page.getByLabel('系统内存',{exact:true}).fill('4096');
      await page.getByLabel('单卡 GPU 显存软预算（可选）',{exact:true}).fill('1024');
      await page.getByLabel('单卡 GPU 显存软预算（可选）',{exact:true}).press('Tab');
      const values=await page.evaluate(()=>window.__qa.values());
      assert(values.cpuCores===2 && values.memoryMiB===4096 && values.gpuMemoryLimitMiB===1024,'资源限制输入仍写入原表单字段');
      assert(await page.getByLabel('CPU 核数',{exact:true}).getAttribute('aria-valuemax')==='4','CPU 上限仍来自硬件回执');
      assert(await page.getByLabel('单卡 GPU 显存软预算（可选）',{exact:true}).getAttribute('aria-valuemax')==='32000','GPU 显存预算上限仍来自硬件回执');
    }
  }
  assert((await page.getByRole('row').filter({hasText:'单卡 GPU 显存软预算'}).innerText()).includes('1 GiB'),'确认页以 1 GiB 显示并保留 1024 MiB 显存预算');
  assert(await page.evaluate(()=>window.__qa.writes)===0,'步骤检查没有触发真实提交');
  return {passed:checks.length,checks};
}
