#!/usr/bin/env python3
"""生成客户安装配置，不执行 sudo、安装、网络访问或集群变更。Python 3.8+。"""
import argparse
import hashlib
import ipaddress
import json
import re
import sys
from pathlib import Path, PurePosixPath

from image_catalog import INTERNAL, collect_catalog, source_text


FIELDS = {'schema_version', 'cluster_name', 'node_name', 'role', 'node_ip',
          'control_plane_endpoint', 'pod_cidr', 'service_cidr', 'data_root', 'source_root',
          'etcd_data_dir', 'containerd_config_version', 'gpu_enabled', 'reserved', 'ports'}
RESERVES = {'system_cpu_millis', 'system_memory_mib', 'kube_cpu_millis', 'kube_memory_mib'}
PORTS = {'frontend', 'backend', 'postgres', 'redis', 'minio', 'minio_console', 'mlflow'}


def exact_fields(value, fields, label):
    if not isinstance(value, dict) or set(value) != fields:
        raise ValueError(label + ' 字段不完整或含未知字段，请按示例填写')


def plain_path(value):
    if (not isinstance(value, str) or not re.fullmatch(r'/[A-Za-z0-9_./-]+', value)
            or str(PurePosixPath(value)) != value or '..' in PurePosixPath(value).parts
            or len(PurePosixPath(value).parts) < 3):
        raise ValueError('需要规范的项目专用绝对路径，不能使用根目录或跳转：' + str(value))
    for protected in ('/etc', '/proc', '/sys', '/dev', '/run', '/boot', '/usr',
                      '/var/lib/docker', '/var/lib/containerd', '/var/lib/kubelet'):
        if value == protected or value.startswith(protected + '/'):
            raise ValueError('不能将系统目录作为项目数据/源码目录：' + value)
    return PurePosixPath(value)


def unicast_ipv4(value):
    address = ipaddress.ip_address(value)
    if (address.version != 4 or address.is_loopback or address.is_unspecified
            or address.is_multicast or address.is_link_local or int(address) >= 0xf0000000):
        raise ValueError('本阶段要求稳定的节点 IPv4 地址')
    return address


def positive_int(value, label, maximum):
    if type(value) is not int or not 0 < value <= maximum:
        raise ValueError(label + ' 必须为合理范围内的正整数')


def validate_config(config):
    exact_fields(config, FIELDS, '节点配置')
    if type(config['schema_version']) is not int or config['schema_version'] != 1:
        raise ValueError('不支持的配置版本')
    for key in ('cluster_name', 'node_name'):
        if not isinstance(config[key], str) or not re.fullmatch(r'[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?', config[key]):
            raise ValueError(key + ' 必须是小写 DNS 名称')
    if config['cluster_name'] == 'tss-aiplatform-internal':
        raise ValueError('客户配置不能复用实验室集群身份')
    if config['role'] not in ('control-compute', 'worker'):
        raise ValueError('角色只能是 control-compute 或 worker')
    if type(config['gpu_enabled']) is not bool:
        raise ValueError('gpu_enabled 必须为布尔值')
    if type(config['containerd_config_version']) is not int or config['containerd_config_version'] not in (2, 3):
        raise ValueError('containerd 配置版本仅支持 2（1.x）或 3（2.x）')
    node = unicast_ipv4(config['node_ip'])
    try:
        endpoint_ip, endpoint_port = config['control_plane_endpoint'].split(':')
        endpoint = unicast_ipv4(endpoint_ip)
    except (AttributeError, ValueError) as exc:
        raise ValueError('控制面入口须为 IPv4:端口') from exc
    if not endpoint_port.isdigit() or not 1 <= int(endpoint_port) <= 65535:
        raise ValueError('控制面端口无效')
    if config['role'] == 'control-compute' and node != endpoint:
        raise ValueError('首台单机控制面入口必须指向本机')
    if config['role'] == 'worker' and node == endpoint:
        raise ValueError('新增工作节点不能复用控制节点地址')
    networks = [ipaddress.ip_network(config[k], strict=True) for k in ('pod_cidr', 'service_cidr')]
    if any(net.version != 4 or not 12 <= net.prefixlen <= 24 for net in networks):
        raise ValueError('Pod/Service 网段须为 /12 至 /24 的 IPv4 网络')
    if networks[0].overlaps(networks[1]) or any(node in net or endpoint in net for net in networks):
        raise ValueError('Pod/Service 网段不能相交或包含节点地址')
    data, source, etcd = [plain_path(config[k]) for k in ('data_root', 'source_root', 'etcd_data_dir')]
    if data == source or data == etcd or source == etcd or source in data.parents:
        raise ValueError('数据根、源码和 etcd 不能互相覆盖')
    runtime_paths = [data / item for item in ('containerd', 'kubelet', 'platform')]
    for path in (source, etcd):
        if any(path == target or target in path.parents or path in target.parents for target in runtime_paths):
            raise ValueError('源码/etcd 不能覆盖运行时或业务数据目录')
    if source in etcd.parents or etcd in source.parents:
        raise ValueError('源码与 etcd 路径不能嵌套')
    exact_fields(config['reserved'], RESERVES, '资源预留')
    for key, value in config['reserved'].items():
        positive_int(value, key, 1048576)
    if config['role'] == 'control-compute':
        if config['reserved']['system_cpu_millis'] < 4000 or config['reserved']['system_memory_mib'] < 6144:
            raise ValueError('控制/计算同机的系统预留至少覆盖五个 Compose 服务并留余量：4 核、6 GiB')
    exact_fields(config['ports'], PORTS, '平台端口')
    used_ports = {int(endpoint_port), 10250, 10257, 10259, 2379, 2380, 9400, 179, 4789}
    for name, port in config['ports'].items():
        positive_int(port, name, 65535)
        if port in used_ports:
            raise ValueError('端口重复或与集群组件冲突：' + name)
        used_ports.add(port)
    return config


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('JSON 字段重复：' + key)
        result[key] = value
    return result


def load_config(path):
    with Path(path).open(encoding='utf-8-sig') as stream:
        return validate_config(json.load(stream, object_pairs_hook=unique_object))


def json_text(value):
    return json.dumps(value, ensure_ascii=False, indent=2) + '\n'


def replace_exact(text, before, after, count=1):
    if text.count(before) != count:
        raise ValueError('源码模板结构改变，需重新审阅替换位置：' + before)
    return text.replace(before, after)


def kubelet_config(config, versions):
    r = config['reserved']
    return dict(apiVersion='kubelet.config.k8s.io/' + versions['TSS_KUBELET_CONFIG_API_VERSION'],
                kind='KubeletConfiguration', cgroupDriver='systemd', failSwapOn=False,
                memorySwap={'swapBehavior': 'NoSwap'}, enforceNodeAllocatable=['pods'],
                systemReserved={'cpu': str(r['system_cpu_millis']) + 'm', 'memory': str(r['system_memory_mib']) + 'Mi'},
                kubeReserved={'cpu': str(r['kube_cpu_millis']) + 'm', 'memory': str(r['kube_memory_mib']) + 'Mi'},
                mergeDefaultEvictionSettings=True, evictionHard={'memory.available': '500Mi'})


def render_runtime(config, pause):
    name, data = config['cluster_name'], config['data_root']
    version = config['containerd_config_version']
    runtime = 'io.containerd.grpc.v1.cri' if version == 2 else 'io.containerd.cri.v1.runtime'
    text = ('# 项目独立运行时；不要覆盖 /etc/containerd/config.toml\n'
            'version = {}\nimports = []\n'
            'root = "{}/containerd"\nstate = "/run/{}/containerd"\n'
            '[grpc]\n  address = "/run/{}/containerd/containerd.sock"\n').format(version, data, name, name)
    if version == 2:
        text += '[plugins."io.containerd.grpc.v1.cri"]\n  sandbox_image = "{}"\n'.format(pause)
    else:
        text += ('[plugins."io.containerd.cri.v1.images"]\n  snapshotter = "overlayfs"\n'
                 '[plugins."io.containerd.cri.v1.images".pinned_images]\n  sandbox = "{}"\n').format(pause)
    # 隔离仓库配置，避免读取同机其他平台的镜像凭据或镜像站配置。
    # imports=[] 禁止本文件导入片段；2.2.1 的 config dump 会展示合并后的
    # 默认 imports，不代表实际读取了该目录，不能把 dump 输出覆盖此配置。
    images = 'io.containerd.grpc.v1.cri' if version == 2 else 'io.containerd.cri.v1.images'
    text += '[plugins."{}".registry]\n  config_path = "/etc/{}/certs.d"\n'.format(images, name)
    text += '[plugins."{}".containerd]\n  default_runtime_name = "runc"\n'.format(runtime)
    for handler in (['runc', 'nvidia'] if config['gpu_enabled'] else ['runc']):
        text += ('[plugins."{0}".containerd.runtimes.{1}]\n  runtime_type = "io.containerd.runc.v2"\n'
                 '[plugins."{0}".containerd.runtimes.{1}.options]\n  SystemdCgroup = true\n').format(runtime, handler)
        if handler == 'nvidia':
            text += '  BinaryName = "/usr/bin/nvidia-container-runtime"\n'
    return text


def render_plan(config, root):
    validate_config(config)
    catalog = collect_catalog(root)
    versions = dict(line.split('=', 1) for line in source_text(root, INTERNAL / 'versions.env').splitlines()
                    if line and not line.startswith('#'))
    c, data, name = config, config['data_root'], config['cluster_name']
    kubelet = kubelet_config(c, versions)
    files = {'node.json': json_text(c), 'image-catalog.json': json_text(catalog),
             'kubelet-config.json': json_text(kubelet),
             'containerd.toml': render_runtime(c, versions['TSS_CONTAINERD_PAUSE_IMAGE'])}
    files['var-lib-kubelet.mount'] = (
        '[Unit]\nDescription=TSS kubelet data bind mount\nRequiresMountsFor={0}/kubelet\n'
        'Before=kubelet.service\n[Mount]\nWhat={0}/kubelet\nWhere=/var/lib/kubelet\n'
        'Type=none\nOptions=bind\n[Install]\nWantedBy=multi-user.target\n').format(data)
    files[name + '-containerd.service'] = (
        '[Unit]\nDescription=TSS customer isolated containerd\nAfter=network-online.target local-fs.target\n'
        'Wants=network-online.target\nRequiresMountsFor={0}/containerd\n[Service]\nType=notify\n'
        'ExecStartPre=/usr/bin/install -d -m 0755 /run/{1}/containerd\n'
        'ExecStart=/usr/bin/containerd --config /etc/{1}/containerd.toml\n'
        'Restart=always\nRestartSec=5\nDelegate=yes\nKillMode=process\nOOMScoreAdjust=-500\n'
        'LimitNOFILE=1048576\n[Install]\nWantedBy=multi-user.target\n').format(data, name)
    files['kubelet-dependencies.conf'] = (
        '[Unit]\nRequires=var-lib-kubelet.mount {0}-containerd.service\n'
        'After=var-lib-kubelet.mount {0}-containerd.service\n').format(name)
    if c['role'] == 'worker':
        join = dict(apiVersion='kubeadm.k8s.io/' + versions['TSS_KUBEADM_API_VERSION'], kind='JoinConfiguration',
                    discovery=dict(bootstrapToken=dict(apiServerEndpoint=c['control_plane_endpoint'],
                                   token='REPLACE_BOOTSTRAP_TOKEN', caCertHashes=['REPLACE_CA_CERT_HASH'])),
                    nodeRegistration=dict(name=c['node_name'], imagePullPolicy='Never', imagePullSerial=True,
                        criSocket='unix:///run/' + name + '/containerd/containerd.sock',
                        taints=[dict(key='tss.ai/staging', value='true', effect='NoSchedule')],
                        kubeletExtraArgs=[dict(name='node-ip', value=c['node_ip'])]),
                    patches=dict(directory='/etc/' + name + '/join-patches'))
        files['kubeadm-join.yaml.template'] = json_text(join)
        # join 从控制面获取集群默认配置，再用本节点 patch 覆盖资源预留。
        # 升级时也需保留该 patch，避免工作节点重新继承控制节点的服务预留。
        files['kubeletconfiguration0+merge.yaml'] = json_text(kubelet)
        files['join-instructions.md'] = (
            '# 加入计算节点（准备稿）\n\n本目录没有控制面初始化配置，不能在工作节点运行 kubeadm init。\n\n'
            '先完成该节点的离线依赖、项目运行时、kubelet 挂载和镜像导入。控制节点生成短期 join token，'
            '通过安全渠道传递，不放入交付包。join 必须使用本节点名、项目 CRI socket 和 CA hash，'
            '初始添加 tss.ai/staging=true:NoSchedule；不得使用跳过 CA 校验参数。\n\n'
            '将 kubeletconfiguration0+merge.yaml 安装到 /etc/' + name + '/join-patches/，'
            '把 kubeadm-join.yaml.template 复制到权限 0600 的运行时目录并填写短期 token 与 CA hash，'
            '先执行 kubeadm config validate --config <已填写的配置>，再由管理员执行 '
            'kubeadm join --config <已填写的配置>。生成器不调用集群，也不生成可登录的 token。\n\n'
            '升级时保留同一 patches 目录。加入后先验证 Ready、标准 /var/lib/kubelet 挂载、'
            'CPU/内存 Allocatable、GPU、网络和镜像，再仅移除该工作节点的 tss.ai/staging 污点。\n')
        return files
    registration = dict(name=c['node_name'], criSocket='unix:///run/' + name + '/containerd/containerd.sock',
                        imagePullPolicy='Never', imagePullSerial=True,
                        # 使用标准控制面污点：CoreDNS 等系统组件已有对应容忍。
                        # 基础验收后只移除当前节点这一项，不全局清除污点。
                        taints=[dict(key='node-role.kubernetes.io/control-plane', effect='NoSchedule')],
                        kubeletExtraArgs=[dict(name='node-ip', value=c['node_ip'])])
    init = dict(apiVersion='kubeadm.k8s.io/' + versions['TSS_KUBEADM_API_VERSION'], kind='InitConfiguration',
                nodeRegistration=registration,
                localAPIEndpoint=dict(advertiseAddress=c['node_ip'], bindPort=int(c['control_plane_endpoint'].split(':')[1])))
    cluster = dict(apiVersion=init['apiVersion'], kind='ClusterConfiguration', clusterName=name,
                   kubernetesVersion=versions['TSS_KUBERNETES_VERSION'], controlPlaneEndpoint=c['control_plane_endpoint'],
                   apiServer=dict(certSANs=[c['node_ip']]), etcd=dict(local=dict(dataDir=c['etcd_data_dir'])),
                   networking=dict(dnsDomain='cluster.local', podSubnet=c['pod_cidr'], serviceSubnet=c['service_cidr']))
    files['kubeadm-init.yaml'] = '\n---\n'.join(json_text(obj).rstrip() for obj in (init, cluster, kubelet)) + '\n'
    render_platform(files, c, root, catalog)
    render_addons(files, c, root, catalog)
    return files


def render_platform(files, c, root, catalog):
    name, data, ports = c['cluster_name'], c['data_root'], c['ports']
    compose = source_text(root, INTERNAL / 'platform/compose.yml')
    compose = replace_exact(compose, 'name: tss-aiplatform-internal\n', 'name: ' + name + '\n')
    compose = replace_exact(compose, 'container_name: tss-aiplatform-internal-', 'container_name: ' + name + '-', 5)
    compose = replace_exact(compose, 'TRAINING_K8S_CLUSTER_NAME: tss-aiplatform-internal', 'TRAINING_K8S_CLUSTER_NAME: ' + name)
    compose = replace_exact(compose, '    pull_policy: never\n', '')
    compose, count = re.subn(r'^(    image: .*\n)', r'\1    pull_policy: never\n', compose, flags=re.MULTILINE)
    if count != 5:
        raise ValueError('Compose 服务数量发生改变，需要重新审阅')
    files['compose.yml'] = compose
    env = dict(TSS_PLATFORM_ROOT=data + '/platform', TSS_REPOSITORY_ROOT=c['source_root'],
               TSS_PLATFORM_BIND_IP=c['node_ip'], TSS_KUBECTL_PATH='/usr/bin/kubectl',
               TSS_CORS_ALLOWED_ORIGIN_PATTERNS='http://{}:{}'.format(c['node_ip'], ports['frontend']))
    for key, field in {'backend': 'BACKEND', 'postgres': 'POSTGRES', 'redis': 'REDIS',
                       'minio': 'MINIO_API', 'minio_console': 'MINIO_CONSOLE', 'mlflow': 'MLFLOW'}.items():
        env['TSS_' + field + '_PORT'] = str(ports[key])
    for row in catalog['images']:
        if row['store'] == 'docker':
            purpose = {'mlflow-lite': 'MLFLOW'}.get(row['purpose'], row['purpose'].upper())
            env['TSS_' + purpose + '_IMAGE'] = row['runtime_ref']
    files['platform.env'] = '# 不含凭据；运行时还需管理员生成的独立 secrets.env\n' + ''.join(k + '=' + v + '\n' for k, v in sorted(env.items()))
    for filename in ('training-namespace.yaml', 'training-resource-policy.yaml', 'training-service-account.yaml'):
        files[filename] = source_text(root, Path('k8s/base') / filename)
    files['backend-access.yaml'] = source_text(root, INTERNAL / 'platform/k8s/backend-access.yaml')
    host = source_text(root, Path('k8s/local/host-services.template.yaml'))
    for key, value in {'__HOST_GATEWAY__': c['node_ip'], '__BACKEND_HOST_PORT__': ports['backend'],
                       '__MINIO_HOST_PORT__': ports['minio'], '__MLFLOW_HOST_PORT__': ports['mlflow']}.items():
        host = replace_exact(host, key, str(value), 3 if key == '__HOST_GATEWAY__' else 1)
    files['host-services.yaml'] = host
    frontend = next(row for row in catalog['images'] if row['purpose'] == 'frontend')
    manifest = source_text(root, INTERNAL / 'platform/k8s/frontend.yaml.template')
    for key, value in {'REPLACE_FRONTEND_SOURCE_SHA': frontend['source'].split(':')[-1],
                       'REPLACE_FRONTEND_IMAGE': frontend['runtime_ref'], 'REPLACE_CONTROL_PLANE_NODE': c['node_name'],
                       'REPLACE_CONTROL_PLANE_IP': c['node_ip'], 'REPLACE_BACKEND_PORT': ports['backend'],
                       'REPLACE_MLFLOW_PORT': ports['mlflow'], 'REPLACE_FRONTEND_PORT': ports['frontend']}.items():
        manifest = replace_exact(manifest, key, str(value), 3 if key == 'REPLACE_CONTROL_PLANE_IP' else 1)
    manifest = replace_exact(manifest, 'app.kubernetes.io/part-of: tss-aiplatform-internal',
                             'app.kubernetes.io/part-of: ' + name, 2)
    files['frontend.yaml'] = staging_toleration(manifest)


def add_toleration(text, key):
    # 暂存污点仅阻止业务任务；基础组件仍可完成启动和验收。
    pattern = r'^( *)tolerations:[ \t]*\n( *)- '
    def add(match):
        # 上游 YAML 同时使用“列表同缩进”和“列表多缩进两格”，必须跟随原列表。
        indent = match.group(2)
        return (match.group(1) + 'tolerations:\n' + indent + '- key: ' + key + '\n' + indent
                + '  operator: Exists\n' + indent + '  effect: NoSchedule\n' + indent + '- ')
    result, count = re.subn(pattern, add, text, flags=re.MULTILINE)
    if count != 1:
        raise ValueError('组件 tolerations 结构改变，需重新审阅')
    return result


def staging_toleration(text):
    return add_toleration(text, 'tss.ai/staging')


def locked_manifest(root, catalog, filename, url_suffix):
    text = source_text(root, INTERNAL / 'manifests' / filename)
    rows = [row for row in catalog['manifests'] if row['source'].endswith(url_suffix)]
    if len(rows) != 1 or hashlib.sha256(text.encode()).hexdigest() != rows[0]['sha256']:
        raise ValueError('基础组件清单与锁不一致：' + filename)
    return text


def render_addons(files, c, root, catalog):
    metrics = locked_manifest(root, catalog, 'metrics-server-components.yaml', '/components.yaml')
    metrics = replace_exact(metrics, '        - --metric-resolution=15s',
                            '        - --metric-resolution=15s\n        - --kubelet-insecure-tls')
    metrics = replace_exact(metrics, '        imagePullPolicy: IfNotPresent', '        imagePullPolicy: Never')
    metrics = replace_exact(metrics, '        kubernetes.io/os: linux',
                            '        kubernetes.io/os: linux\n        kubernetes.io/hostname: ' + c['node_name'])
    metrics = replace_exact(metrics, '      priorityClassName: system-cluster-critical',
                            '      tolerations:\n      - key: node-role.kubernetes.io/control-plane\n'
                            '        operator: Exists\n        effect: NoSchedule\n'
                            '      priorityClassName: system-cluster-critical')
    files['metrics-server.yaml'] = staging_toleration(metrics)
    if not c['gpu_enabled']:
        return
    plugin = locked_manifest(root, catalog, 'nvidia-device-plugin.yml', '/nvidia-device-plugin.yml')
    plugin = replace_exact(plugin, '    spec:\n', '    spec:\n      runtimeClassName: nvidia\n'
                            '      nodeSelector:\n        tss.ai/accelerator: nvidia\n')
    plugin = re.sub(r'^(      - image: .*)$', r'\1\n        imagePullPolicy: Never', plugin, flags=re.MULTILINE)
    plugin = replace_exact(plugin, '            value: "false"', '            value: "true"')
    files['nvidia-device-plugin.yaml'] = staging_toleration(add_toleration(plugin, 'node-role.kubernetes.io/control-plane'))
    files['runtime-class.json'] = json_text(dict(apiVersion='node.k8s.io/v1', kind='RuntimeClass',
                                                metadata=dict(name='nvidia'), handler='nvidia'))
    files['dcgm-exporter.yaml'] = staging_toleration(source_text(root, INTERNAL / 'manifests/dcgm-exporter.yaml'))


def write_plan(output, files):
    output = Path(output)
    for path in [output] + list(output.parents):
        if path.is_symlink():
            raise ValueError('输出目录及父目录不能是符号链接')
    # 必须用新目录；不覆盖旧计划/已编辑凭据，也不删除失败现场。
    output.mkdir(mode=0o700, parents=False, exist_ok=False)
    checksums = []
    for name, text in sorted(files.items()):
        payload = text.encode('utf-8')
        with (output / name).open('xb') as stream:
            stream.write(payload)
        checksums.append(hashlib.sha256(payload).hexdigest() + '  ' + name)
    with (output / 'SHA256SUMS').open('x', encoding='ascii', newline='\n') as stream:
        stream.write('\n'.join(checksums) + '\n')
    # 最后生成完成标记；写入中断留下的目录不能被当作完整安装配置。
    manifest = {name: hashlib.sha256(text.encode('utf-8')).hexdigest() for name, text in files.items()}
    manifest['SHA256SUMS'] = hashlib.sha256((output / 'SHA256SUMS').read_bytes()).hexdigest()
    with (output / 'PLAN_COMPLETE.json').open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json_text({'schema_version': 1, 'files': manifest}))


def verify_plan(output):
    output = Path(output)
    for path in [output] + list(output.parents):
        if path.is_symlink():
            raise ValueError('配置目录及父目录不能是符号链接')
    marker = output / 'PLAN_COMPLETE.json'
    if marker.is_symlink() or not marker.is_file():
        raise ValueError('配置目录未完整生成')
    manifest = json.loads(marker.read_text(encoding='utf-8'), object_pairs_hook=unique_object)
    exact_fields(manifest, {'schema_version', 'files'}, '配置完成标记')
    if manifest['schema_version'] != 1 or not isinstance(manifest['files'], dict) or not manifest['files']:
        raise ValueError('配置完成标记无效')
    if {p.name for p in output.iterdir()} != set(manifest['files']) | {'PLAN_COMPLETE.json'}:
        raise ValueError('配置文件清单有缺失或额外文件')
    for name, digest in manifest['files'].items():
        if (not isinstance(name, str) or Path(name).name != name or '/' in name or '\\' in name
                or not isinstance(digest, str) or not re.fullmatch('[0-9a-f]{64}', digest)):
            raise ValueError('文件清单名称或摘要无效')
        path = output / name
        if path.is_symlink() or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError('配置文件被修改或损坏：' + name)
    return len(manifest['files'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config')
    parser.add_argument('--output', help='父目录已存在的全新输出目录')
    parser.add_argument('--verify-output', help='只校验已生成的配置目录，不执行安装')
    args = parser.parse_args()
    if bool(args.verify_output) == bool(args.config or args.output) or (not args.verify_output and not (args.config and args.output)):
        parser.error('使用 --config 与 --output 生成，或仅用 --verify-output 校验')
    try:
        if args.verify_output:
            count = verify_plan(args.verify_output)
            print('配置完整性校验通过：{} 个文件；不是镜像或安装验收。'.format(count))
            return 0
        files = render_plan(load_config(args.config), Path(__file__).resolve().parents[2])
        write_plan(args.output, files)
    except (OSError, ValueError, TypeError, KeyError) as exc:
        print('ERROR: ' + str(exc), file=sys.stderr)
        return 1
    print('已生成 {} 个配置文件；没有安装软件、创建集群或验证离线镜像。'.format(len(files)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
