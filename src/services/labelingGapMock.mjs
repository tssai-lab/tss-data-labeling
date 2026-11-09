// Missing capabilities only. Never imports the authenticated HTTP client or accepts real IDs.
export class GapMockError extends Error {
  constructor(status, code, message) {
    super(message);
    Object.assign(this, {
      status,
      code,
      requestId: `demo-${crypto.randomUUID()}`,
    });
  }
}
export function createGapMock({
  enabled = false,
  production = true,
  now = () => Date.now(),
} = {}) {
  let state;
  let serial = 0;
  const receipts = new Map();
  const check = () => {
    if (!enabled || production)
      throw new GapMockError(
        503,
        'MOCK_DISABLED',
        '待后端接入：本地演示未启用',
      );
  };
  const fail = (status, code, message) => {
    throw new GapMockError(status, code, message);
  };
  const copy = (value) => structuredClone(value);
  function reset() {
    check();
    receipts.clear();
    serial = 0;
    const samples = Array.from({ length: 12 }, (_, i) => ({
      sampleId: `demo-sample-${i + 1}`,
      batchId: 'demo-batch',
      sourcePath: `合成图片/很长的来源目录与文件名用于窄屏验收/${'sample-'.repeat(9)}${i + 1}.png`,
      checksum: '0'.repeat(64),
    }));
    const labels = [
      { id: 'normal', name: '正常' },
      { id: 'none', name: '无目标' },
      { id: 'defect', name: '缺陷' },
    ];
    state = {
      project: {
        projectId: 'demo-project',
        ownerActorId: 'demo-owner',
        name: '合成演示 · 图片单标签分类',
        status: 'OPEN',
        revision: 1,
      },
      batches: [
        {
          batchId: 'demo-batch',
          projectId: 'demo-project',
          status: 'READY',
          validSampleCount: 12,
        },
        {
          batchId: 'demo-failed-batch',
          projectId: 'demo-project',
          status: 'FAILED',
          validSampleCount: 0,
          errorCode: 'NO_VALID_SAMPLES',
        },
      ],
      packages: [
        { packageId: 'demo-package-1', sampleCount: 10, requiredCount: 2 },
        { packageId: 'demo-package-2', sampleCount: 2, requiredCount: 2 },
      ],
      samples,
      specification: {
        specVersionId: 'demo-spec-v1',
        labels,
        instructions: '合成规范说明。'.repeat(50),
      },
      assignments: [
        {
          assignmentId: 'demo-assignment-a',
          actorId: 'demo-alice',
          packageId: 'demo-package-1',
          status: 'SUBMITTED',
          revision: 3,
        },
        {
          assignmentId: 'demo-assignment-b',
          actorId: 'demo-bob',
          packageId: 'demo-package-1',
          status: 'SUBMITTED',
          revision: 3,
        },
        {
          assignmentId: 'demo-assignment-open',
          actorId: 'demo-alice',
          packageId: 'demo-package-2',
          status: 'IN_PROGRESS',
          revision: 2,
        },
      ],
      submissions: ['a', 'b'].map((n, i) => ({
        submissionId: `demo-submission-${n}`,
        projectId: 'demo-project',
        packageId: 'demo-package-1',
        assignmentId: `demo-assignment-${n}`,
        submittedBy: i ? 'demo-bob' : 'demo-alice',
        specVersionId: 'demo-spec-v1',
        items: samples
          .slice(0, 10)
          .map((s) => ({
            sampleId: s.sampleId,
            revision: 1,
            resultFormat: 'image-classification-v1',
            result: { labelId: i ? 'none' : 'normal' },
          })),
      })),
      exports: [],
      audit: [],
    };
    return copy(state);
  }
  function auth(actor) {
    if (
      ![
        'demo-owner',
        'demo-admin',
        'demo-alice',
        'demo-bob',
        'demo-outsider',
      ].includes(actor)
    )
      fail(403, 'FORBIDDEN', '仅接受合成身份');
  }
  function owner(actor) {
    if (actor !== state.project.ownerActorId)
      fail(404, 'RESOURCE_NOT_FOUND', '无项目数据权限（管理员无原图特权）');
  }
  function project(id) {
    if (id !== state.project.projectId)
      fail(404, 'RESOURCE_NOT_FOUND', '仅接受演示项目');
  }
  function revision(actual, expected) {
    if (!Number.isInteger(expected) || actual !== expected)
      fail(409, 'REVISION_CONFLICT', '版本已变化，请重新读取');
  }
  function reason(text) {
    if (typeof text !== 'string' || !text.trim() || text.trim().length > 500)
      fail(400, 'INVALID_REQUEST', '请填写 1–500 字原因');
  }
  function audit(actor, operation, target, text) {
    state.audit.unshift({
      eventId: `demo-event-${++serial}`,
      actorId: actor,
      operation,
      target,
      reason: text || '',
      createdAt: new Date(now()).toISOString(),
    });
  }
  function artifact(job) {
    const submissions = job.submissionIds.map((id) =>
      state.submissions.find((s) => s.submissionId === id),
    );
    const records = submissions.flatMap((s) =>
      s.items.map((item) => ({
        projectId: s.projectId,
        batchId: 'demo-batch',
        sampleId: item.sampleId,
        packageId: s.packageId,
        assignmentId: s.assignmentId,
        actorId: s.submittedBy,
        submissionId: s.submissionId,
        specVersionId: s.specVersionId,
        sourcePath: state.samples.find((v) => v.sampleId === item.sampleId)
          .sourcePath,
        checksum: '0'.repeat(64),
        ...item,
      })),
    );
    const fields = [
      'projectId',
      'batchId',
      'sampleId',
      'packageId',
      'assignmentId',
      'actorId',
      'submissionId',
      'specVersionId',
      'sourcePath',
      'checksum',
    ];
    const csv = (v) => `"${String(v ?? '').replaceAll('"', '""')}"`;
    return {
      'results.json': JSON.stringify(
        {
          synthetic: true,
          resultFormat: 'image-classification-v1',
          submissions,
          records,
        },
        null,
        2,
      ),
      'mapping.csv':
        '\uFEFF' +
        fields.join(',') +
        '\r\n' +
        records.map((r) => fields.map((f) => csv(r[f])).join(',')).join('\r\n'),
      'manifest.json': JSON.stringify(
        {
          synthetic: true,
          exportId: job.exportId,
          projectId: 'demo-project',
          submissionIds: job.submissionIds,
          resultCount: records.length,
          packages: state.packages.map((p) => ({
            ...p,
            submittedCount: submissions.filter(
              (s) => s.packageId === p.packageId,
            ).length,
          })),
          incompleteSampleIds: state.samples.slice(10).map((s) => s.sampleId),
          checksumsAreSynthetic: true,
        },
        null,
        2,
      ),
      'labels.json': JSON.stringify(
        {
          synthetic: true,
          specifications: [state.specification],
          emptySelectionMeans: '未完成，不能导出为正常或无目标',
        },
        null,
        2,
      ),
    };
  }
  function request(
    method,
    path,
    { actor = 'demo-owner', body = {}, key } = {},
  ) {
    check();
    if (!state) reset();
    auth(actor);
    const url = new URL(path, 'https://demo.invalid');
    const parts = url.pathname.split('/').filter(Boolean);
    if (!url.pathname.startsWith('/api/labeling/v1/'))
      fail(404, 'RESOURCE_NOT_FOUND', '未知演示接口');
    const route = parts.slice(3);
    let receipt;
    if (method === 'POST') {
      if (!key || key.length > 200) fail(400, 'INVALID_REQUEST', '缺少幂等键');
      receipt = `${actor}:${method}:${url.pathname}:${key}`;
      if (receipts.has(receipt)) {
        const saved = receipts.get(receipt);
        if (saved.body !== JSON.stringify(body))
          fail(409, 'IDEMPOTENCY_CONFLICT', '同键不同请求');
        return copy(saved.result);
      }
    }
    let data;
    if (route[0] === 'projects') {
      project(route[1]);
      const action = route[2];
      if (method === 'GET' && action === 'imports') {
        owner(actor);
        data = { items: state.batches, nextCursor: null };
      } else if (
        method === 'POST' &&
        ['pause', 'resume', 'archive'].includes(action)
      ) {
        if (actor !== 'demo-owner' && actor !== 'demo-admin')
          fail(403, 'FORBIDDEN', '需要项目需求方或标注管理员');
        revision(state.project.revision, body.baseRevision);
        if (action !== 'resume') reason(body.reason);
        if (
          state.project.status === 'ARCHIVED' ||
          (action === 'pause' && state.project.status !== 'OPEN') ||
          (action === 'resume' && state.project.status !== 'PAUSED')
        )
          fail(409, 'PROJECT_STATE_CONFLICT', '当前状态不允许该转换');
        if (
          action === 'archive' &&
          state.assignments.some((a) =>
            ['CLAIMED', 'IN_PROGRESS'].includes(a.status),
          )
        )
          fail(409, 'RESOURCE_BUSY', '仍有有效领取，不能归档');
        state.project.status = {
          pause: 'PAUSED',
          resume: 'OPEN',
          archive: 'ARCHIVED',
        }[action];
        state.project.revision++;
        audit(actor, action, route[1], body.reason);
        data = state.project;
      } else if (method === 'POST' && action === 'exports') {
        owner(actor);
        if (body.format !== 'native-json' || body.includeOriginals !== false)
          fail(422, 'UNSUPPORTED_FORMAT', '仅支持 native-json，不含原图');
        if (!['ALL_SUBMITTED', 'SELECTED_SUBMISSIONS'].includes(body.scope))
          fail(400, 'INVALID_REQUEST', '无效导出范围');
        const ids =
          body.scope === 'ALL_SUBMITTED'
            ? state.submissions.map((s) => s.submissionId)
            : body.submissionIds;
        if (!Array.isArray(ids) || !ids.length)
          fail(422, 'NO_SUBMISSIONS', '没有已提交结果');
        if (
          new Set(ids).size !== ids.length ||
          ids.some(
            (id) => !state.submissions.some((s) => s.submissionId === id),
          )
        )
          fail(404, 'RESOURCE_NOT_FOUND', '提交不存在或不属于该项目');
        data = {
          exportId: `demo-export-${++serial}`,
          status: 'QUEUED',
          projectId: route[1],
          submissionIds: [...ids],
          created: now(),
          expires: now() + 60000,
          polls: 0,
          fail: false,
        };
        state.exports.push(data);
        audit(actor, 'export', data.exportId);
      } else fail(404, 'RESOURCE_NOT_FOUND', '未知演示接口');
    } else if (route[0] === 'exports') {
      owner(actor);
      const job = state.exports.find((j) => j.exportId === route[1]);
      if (!job) fail(404, 'RESOURCE_NOT_FOUND', '导出任务不存在');
      if (method !== 'GET') fail(404, 'RESOURCE_NOT_FOUND', '未知演示接口');
      if (route[2] === 'download') {
        if (job.status !== 'SUCCEEDED')
          fail(409, 'EXPORT_NOT_READY', '导出尚未完成');
        if (now() >= job.expires)
          fail(410, 'EXPORT_EXPIRED', '产物已过期，请创建新导出');
        data = artifact(job);
      } else {
        job.polls++;
        job.status =
          job.polls === 1 ? 'RUNNING' : job.fail ? 'FAILED' : 'SUCCEEDED';
        job.error = job.fail
          ? { code: 'STORAGE_UNAVAILABLE', message: '合成存储故障，请重新创建' }
          : null;
        data = job;
      }
    } else if (
      (route[0] === 'assignments' && route[2] === 'release') ||
      (route[0] === 'admin' &&
        route[1] === 'assignments' &&
        route[3] === 'revoke')
    ) {
      if (method !== 'POST') fail(404, 'RESOURCE_NOT_FOUND', '未知演示接口');
      const revoke = route[0] === 'admin';
      const a = state.assignments.find(
        (a) => a.assignmentId === route[revoke ? 2 : 1],
      );
      if (!a || (!revoke && a.actorId !== actor))
        fail(404, 'RESOURCE_NOT_FOUND', '领取不存在');
      if (revoke && actor !== 'demo-admin')
        fail(403, 'FORBIDDEN', '需要标注管理员');
      reason(body.reason);
      revision(a.revision, body.baseRevision);
      if (!['CLAIMED', 'IN_PROGRESS'].includes(a.status))
        fail(409, 'ASSIGNMENT_CLOSED', '已结束领取不能释放');
      a.status = revoke ? 'REVOKED' : 'RELEASED';
      a.revision++;
      audit(actor, revoke ? 'revoke' : 'release', a.assignmentId, body.reason);
      data = a;
    } else if (
      route[0] === 'admin' &&
      ['audit-events', 'projects', 'assignments'].includes(route[1]) &&
      method === 'GET'
    ) {
      if (actor !== 'demo-admin') fail(403, 'FORBIDDEN', '需要标注管理员');
      const all =
        route[1] === 'audit-events'
          ? state.audit
          : route[1] === 'projects'
            ? [state.project]
            : state.assignments;
      const q = url.searchParams.get('q') || '';
      data = {
        items: all.filter((row) => JSON.stringify(row).includes(q)),
        nextCursor: null,
      };
    } else fail(404, 'RESOURCE_NOT_FOUND', '非缺失能力不会被本地 mock 接管');
    const result = { data: copy(data), requestId: `demo-request-${++serial}` };
    if (receipt) receipts.set(receipt, { body: JSON.stringify(body), result });
    return result;
  }
  return {
    reset,
    request,
    snapshot() {
      check();
      if (!state) reset();
      return copy(state);
    },
    setExportFault(id, { failed = false, expired = false } = {}) {
      check();
      const job = state?.exports.find((j) => j.exportId === id);
      if (!job) fail(404, 'RESOURCE_NOT_FOUND', '任务不存在');
      job.fail = failed;
      if (expired) job.expires = now() - 1;
    },
    assertWorkAccess(actor, assignmentId) {
      check();
      auth(actor);
      const a = state?.assignments.find(
        (a) => a.assignmentId === assignmentId && a.actorId === actor,
      );
      if (!a) fail(404, 'RESOURCE_NOT_FOUND', '领取不存在');
      if (state.project.status !== 'OPEN')
        fail(409, 'PROJECT_PAUSED', '项目暂停或归档，工作数据不可访问');
      if (!['CLAIMED', 'IN_PROGRESS'].includes(a.status))
        fail(409, 'ASSIGNMENT_CLOSED', '领取已结束，工作数据不可访问');
      return true;
    },
  };
}
