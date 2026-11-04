#!/usr/bin/env python3
"""甲方新装前的只读检查；兼容 Python 3.8，不安装或修改任何系统设置。"""

import argparse
import json
import os
import platform
import re
import shlex
import shutil
import subprocess
import sys
from pathlib import Path


def read_os_release(text):
    result = {}
    for line in text.splitlines():
        if not line or line.startswith('#') or '=' not in line:
            continue
        key, raw = line.split('=', 1)
        if key not in ('ID', 'VERSION_ID', 'PRETTY_NAME'):
            continue
        values = shlex.split(raw)
        if len(values) == 1:
            result[key] = values[0]
    return result


def version_pair(value):
    match = re.match(r'^(\d+)\.(\d+)(?:\D|$)', str(value))
    return tuple(map(int, match.groups())) if match else None


def nearest_existing_directory(value):
    candidate = Path(value)
    if not candidate.is_absolute():
        raise ValueError('项目数据路径必须是绝对路径')
    if '..' in candidate.parts:
        raise ValueError('项目数据路径不能含上级目录跳转')
    # 不跟随符号链接，以免项目路径实际指向其他人的存储目录。
    for part in [candidate] + list(candidate.parents):
        if part.is_symlink():
            raise ValueError('项目数据路径及父目录不能是符号链接')
    while not candidate.exists():
        candidate = candidate.parent
    if not candidate.is_dir():
        raise ValueError('项目数据路径指向文件而不是目录')
    return candidate


def run_readonly(command):
    try:
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                timeout=10, text=True, check=False)
        return result.stdout.strip() if result.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def collect(data_path):
    target = nearest_existing_directory(data_path)
    os_file = Path('/etc/os-release')
    system = read_os_release(os_file.read_text()) if os_file.is_file() else {}
    memory = {}
    mem_file = Path('/proc/meminfo')
    if mem_file.is_file():
        for line in mem_file.read_text().splitlines():
            key, _, raw = line.partition(':')
            if key in ('MemTotal', 'MemAvailable'):
                memory[key] = int(raw.split()[0]) * 1024
    gpu_text = run_readonly(['nvidia-smi', '--query-gpu=name,driver_version,memory.total',
                            '--format=csv,noheader,nounits'])
    tools = {name: bool(shutil.which(name)) for name in
             ('docker', 'containerd', 'kubeadm', 'kubelet', 'kubectl', 'nvidia-container-runtime')}
    addresses_text = run_readonly(['ip', '-j', '-4', 'address', 'show', 'scope', 'global'])
    ports_text = run_readonly(['ss', '-H', '-lntu'])
    try:
        addresses = [entry['local'] for interface in json.loads(addresses_text)
                     for entry in interface.get('addr_info', []) if entry.get('family') == 'inet']
    except (ValueError, TypeError, KeyError):
        addresses = None
    return {
        'system': platform.system(), 'os': system, 'architecture': platform.machine(),
        'kernel': platform.release(), 'cgroup_v2': Path('/sys/fs/cgroup/cgroup.controllers').is_file(),
        'cpu_logical_cores': os.cpu_count(), 'memory_bytes': memory,
        'data_path': str(data_path), 'checked_existing_parent': str(target),
        'data_filesystem_free_bytes': shutil.disk_usage(target).free,
        'root_filesystem_free_bytes': shutil.disk_usage(Path('/')).free,
        'gpu_summary': gpu_text.splitlines() if gpu_text else [], 'commands': tools,
        'host_ipv4_addresses': addresses,
        'listening_ports': parse_listening_ports(ports_text),
        'existing_kubernetes': Path('/etc/kubernetes/admin.conf').exists()
        or Path('/etc/kubernetes/kubelet.conf').exists(),
    }


def parse_listening_ports(text):
    if text is None:
        return None
    ports = set()
    for line in text.splitlines():
        fields = line.split()
        if len(fields) < 5 or not fields[4].rsplit(':', 1)[-1].isdigit():
            return None  # 无法理解现场输出时不把它解释为“无端口占用”。
        ports.add(int(fields[4].rsplit(':', 1)[-1]))
    return sorted(ports)


def assess_configuration(facts, config):
    """将用户填写的部署计划与本机事实核对，不把计划里的容量当作硬件事实。"""
    failures = []
    if facts.get('host_ipv4_addresses') is None or config['node_ip'] not in facts['host_ipv4_addresses']:
        failures.append('计划节点 IP 不属于本机或未能读取实际地址。')
    reserved = config['reserved']
    cpu = facts.get('cpu_logical_cores')
    memory = facts.get('memory_bytes', {}).get('MemTotal')
    if not cpu or cpu * 1000 <= reserved['system_cpu_millis'] + reserved['kube_cpu_millis']:
        failures.append('实际 CPU 不足以满足资源预留并留给 Pod 使用。')
    reserved_mib = reserved['system_memory_mib'] + reserved['kube_memory_mib'] + 500
    if not memory or memory <= reserved_mib * 1024 * 1024:
        failures.append('实际内存不足以满足系统/Kubernetes/驱逐预留并留给 Pod 使用。')
    ports = facts.get('listening_ports')
    if ports is None:
        failures.append('无法读取监听端口，不能证明安装端口空闲。')
    else:
        needed = {10250}
        if config['role'] == 'control-compute':
            needed.update(config['ports'].values())
            needed.update({2379, 2380, int(config['control_plane_endpoint'].split(':')[1])})
        if config['gpu_enabled']:
            needed.add(9400)
        conflicts = needed.intersection(ports)
        if conflicts:
            failures.append('计划端口已被占用：' + ', '.join(map(str, sorted(conflicts))))
    if config['data_root'] != facts.get('data_path'):
        failures.append('检查的数据目录与配置不一致。')
    return failures


def assess(facts, require_gpu=True):
    """目标是发现准备工作，不把未装依赖或未做功能验收伪装成可交付。"""
    failures = []
    warnings = []
    os_info = facts.get('os', {})
    version = version_pair(os_info.get('VERSION_ID', ''))
    if facts.get('system') != 'Linux' or os_info.get('ID') != 'ubuntu':
        failures.append('本交付路径要求已安装 Ubuntu 22.04 及以上；不负责安装操作系统。')
    elif version is None or version < (22, 4):
        failures.append('Ubuntu 版本无法识别或低于 22.04 支持目标。')
    else:
        warnings.append('Ubuntu {} 属于适配目标，但本检查不证明该版本已实机验收。'.format(
            os_info.get('VERSION_ID')))
    if facts.get('architecture') not in ('x86_64', 'amd64'):
        failures.append('当前镜像交付基线为 linux/amd64，不可直接用于其他 CPU 架构。')
    kernel = version_pair(facts.get('kernel', ''))
    if kernel is None or kernel < (5, 8):
        failures.append('当前 cgroup v2 路径要求 Linux 内核至少 5.8；先规划内核准备，不自动升级。')
    if facts.get('cgroup_v2') is not True:
        failures.append('尚未启用 cgroup v2；需按系统版本准备并在获准重启后复查。')
    if require_gpu and not facts.get('gpu_summary'):
        failures.append('NVIDIA 驱动未能报告 GPU；需准备或修复驱动，不能证明 GPU 可用于训练。')
    if facts.get('existing_kubernetes'):
        failures.append('发现已有 Kubernetes 配置；先确认归属，不可作为空机初始化。')
    missing = [name for name, available in facts.get('commands', {}).items() if not available
               and (require_gpu or name != 'nvidia-container-runtime')]
    if missing:
        warnings.append('缺少平台运行依赖，需按目标服务器版本补齐（不准备 Ubuntu 系统）：' + ', '.join(missing))
    warnings.append('尚未检查镜像完整性、端口/网段冲突、资源预留、磁盘容量预算和真实训练。')
    return {'status': 'not_ready' if failures else 'basic_compatible',
            'failures': failures, 'warnings': warnings, 'installation_verified': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data-path', help='计划使用的项目数据绝对路径；不会创建目录')
    parser.add_argument('--config', help='客户节点 JSON 配置；额外核对实际地址、端口和资源预留')
    args = parser.parse_args()
    if not args.data_path and not args.config:
        parser.error('至少提供 --data-path 或 --config')
    try:
        config = None
        if args.config:
            from deployment_plan import load_config
            config = load_config(args.config)
        facts = collect(args.data_path or config['data_root'])
        assessment = assess(facts, require_gpu=config['gpu_enabled'] if config else True)
        if config:
            assessment['failures'].extend(assess_configuration(facts, config))
            if assessment['failures']:
                assessment['status'] = 'not_ready'
        result = dict(facts=facts, assessment=assessment)
    except (OSError, ValueError) as exc:
        print(json.dumps({'error': str(exc), 'installation_verified': False}, ensure_ascii=False))
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 1 if result['assessment']['failures'] else 0


if __name__ == '__main__':
    sys.exit(main())
