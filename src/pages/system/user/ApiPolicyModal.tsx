import {
  Button,
  InputNumber,
  Modal,
  message,
  Space,
  Switch,
  Table,
  Tag,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useEffect, useRef, useState } from 'react';
import {
  fetchUserApiPolicies,
  resetUserApiPolicy,
  type UserApiPolicy,
  type UserItem,
  updateUserApiPolicy,
} from '@/services/system';
import { notifyRequestError } from '../notifyRequestError';

interface Props {
  target: UserItem | null;
  onClose: () => void;
}

type Draft = Pick<UserApiPolicy, 'enabled' | 'maxConcurrentRequests'>;

const ApiPolicyModal: React.FC<Props> = ({ target, onClose }) => {
  const [policies, setPolicies] = useState<UserApiPolicy[]>([]);
  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const [loading, setLoading] = useState(false);
  const [savingGroup, setSavingGroup] = useState<string>();
  const activeTargetId = useRef<number | null>(null);
  const loadSequence = useRef(0);

  const load = async (userId: number) => {
    if (activeTargetId.current !== userId) return;
    const requestSequence = ++loadSequence.current;
    setLoading(true);
    try {
      const response = await fetchUserApiPolicies(userId);
      if (
        activeTargetId.current !== userId ||
        requestSequence !== loadSequence.current
      ) {
        return;
      }
      if (response.code !== 200 || !response.data) {
        message.error(response.message || 'API 权限读取失败');
        return;
      }
      setPolicies(response.data);
      setDrafts(
        Object.fromEntries(
          response.data.map((policy) => [
            policy.featureGroup,
            {
              enabled: policy.enabled,
              maxConcurrentRequests: policy.maxConcurrentRequests,
            },
          ]),
        ),
      );
    } catch (error: unknown) {
      if (
        activeTargetId.current === userId &&
        requestSequence === loadSequence.current
      ) {
        notifyRequestError(error, 'API 权限读取失败');
      }
    } finally {
      if (
        activeTargetId.current === userId &&
        requestSequence === loadSequence.current
      ) {
        setLoading(false);
      }
    }
  };

  useEffect(() => {
    activeTargetId.current = target?.id ?? null;
    loadSequence.current += 1;
    if (target) void load(target.id);
    else {
      setLoading(false);
      setPolicies([]);
      setDrafts({});
    }
  }, [target?.id]);

  const save = async (policy: UserApiPolicy) => {
    if (!target || savingGroup) return;
    const draft = drafts[policy.featureGroup] ?? policy;
    setSavingGroup(policy.featureGroup);
    try {
      const response = await updateUserApiPolicy(
        target.id,
        policy.featureGroup,
        {
          enabled: draft.enabled,
          maxConcurrentRequests: draft.maxConcurrentRequests,
          version: policy.version,
        },
      );
      if (response.code !== 200) {
        message.error(response.message || '策略保存失败');
        return;
      }
      message.success('策略已生效');
      await load(target.id);
    } catch (error: unknown) {
      notifyRequestError(error, '策略保存失败，请刷新后重试');
      await load(target.id);
    } finally {
      setSavingGroup(undefined);
    }
  };

  const reset = async (policy: UserApiPolicy) => {
    if (!target || policy.inherited || savingGroup) return;
    setSavingGroup(policy.featureGroup);
    try {
      const response = await resetUserApiPolicy(
        target.id,
        policy.featureGroup,
        policy.version,
      );
      if (response.code !== 200) {
        message.error(response.message || '恢复默认失败');
        return;
      }
      message.success('已恢复为角色默认权限');
      await load(target.id);
    } catch (error: unknown) {
      notifyRequestError(error, '恢复默认失败，请刷新后重试');
      await load(target.id);
    } finally {
      setSavingGroup(undefined);
    }
  };

  const updateDraft = (
    featureGroup: string,
    patch: Partial<Draft>,
    fallback: UserApiPolicy,
  ) => {
    setDrafts((current) => {
      const currentDraft = current[featureGroup] ?? {
        enabled: fallback.enabled,
        maxConcurrentRequests: fallback.maxConcurrentRequests,
      };
      return {
        ...current,
        [featureGroup]: {
          ...currentDraft,
          ...patch,
        },
      };
    });
  };

  const columns: ColumnsType<UserApiPolicy> = [
    {
      title: '功能组',
      dataIndex: 'displayName',
      render: (value, policy) => (
        <Space>
          <span>{value}</span>
          <Tag color={policy.inherited ? 'default' : 'blue'}>
            {policy.inherited ? '继承角色默认值' : '用户单独设置'}
          </Tag>
        </Space>
      ),
    },
    {
      title: '允许调用',
      width: 120,
      render: (_, policy) => (
        <Switch
          checked={drafts[policy.featureGroup]?.enabled ?? policy.enabled}
          checkedChildren="允许"
          unCheckedChildren="禁用"
          onChange={(enabled) =>
            updateDraft(policy.featureGroup, { enabled }, policy)
          }
        />
      ),
    },
    {
      title: '并发上限',
      width: 180,
      render: (_, policy) => (
        <InputNumber
          min={1}
          precision={0}
          placeholder="留空表示不限流"
          value={drafts[policy.featureGroup]?.maxConcurrentRequests}
          onChange={(value) =>
            updateDraft(
              policy.featureGroup,
              { maxConcurrentRequests: value == null ? null : value },
              policy,
            )
          }
        />
      ),
    },
    {
      title: '操作',
      width: 180,
      render: (_, policy) => (
        <Space>
          <Button
            type="link"
            loading={savingGroup === policy.featureGroup}
            onClick={() => void save(policy)}
          >
            保存
          </Button>
          <Button
            type="link"
            disabled={policy.inherited || !!savingGroup}
            onClick={() => void reset(policy)}
          >
            恢复默认
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <Modal
      title={target ? `API 权限与并发 - ${target.username}` : 'API 权限与并发'}
      open={!!target}
      width={880}
      onCancel={onClose}
      footer={<Button onClick={onClose}>关闭</Button>}
      destroyOnClose
    >
      <p>
        禁用后立即拒绝该用户的对应功能请求；恢复无需重启或重新登录。并发上限按“该用户
        + 功能组”计算，留空表示不限流。正在运行的训练或推理任务不会被主动删除。
      </p>
      <Table<UserApiPolicy>
        rowKey="featureGroup"
        columns={columns}
        dataSource={policies}
        loading={loading}
        pagination={false}
        size="small"
      />
    </Modal>
  );
};

export default ApiPolicyModal;
