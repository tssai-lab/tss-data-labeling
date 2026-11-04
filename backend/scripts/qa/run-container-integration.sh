#!/usr/bin/env bash
# 在无业务容器的本机 Docker 上运行真实依赖测试；不下载镜像、不改宿主服务。
set -Eeuo pipefail
umask 077
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd -- "$script_dir/../../.." && pwd)
maven_image=maven:3.9.12-eclipse-temurin-17
images=("$maven_image" postgres:16.6-alpine minio/minio:RELEASE.2025-09-07T16-13-09Z redis:7.4.11-alpine testcontainers/ryuk:0.12.0 alpine:3.17)

if [[ ${1:-} == --help || $# -eq 0 ]]; then
  echo "用法：TSS_MAVEN_REPOSITORY=/缓存/repository bash $0 新的绝对证据目录 [--all]"
  echo "只预检：bash $0 --preflight；镜像需事先准备，--all 同时运行后端全部普通测试。"
  exit 0
fi
for tool in docker python3 tar realpath; do command -v "$tool" >/dev/null; done
[[ -z ${DOCKER_HOST:-} || ${DOCKER_HOST} == unix:///var/run/docker.sock ]] || { echo '拒绝远端 Docker'; exit 2; }
# 显式锁定本机 socket，避免当前 Docker context 指向共享或远端环境。
export DOCKER_HOST=unix:///var/run/docker.sock
unset DOCKER_CONTEXT
docker info >/dev/null
[[ -z $(docker ps -q) ]] || { echo '请使用没有运行中业务容器的独立 Docker 环境'; exit 2; }
for image in "${images[@]}"; do
  docker image inspect "$image" >/dev/null || { echo "缺少固定镜像：$image；本脚本不会下载"; exit 2; }
done
if [[ ${1:-} == --preflight ]]; then echo '固定镜像与 Docker 预检通过'; exit 0; fi
[[ $# -le 2 && ( ${2:-} == '' || ${2:-} == --all ) ]] || { echo '未知参数'; exit 2; }
[[ $1 == /* && ! -e $1 ]] || { echo '证据目录必须是尚不存在的绝对路径'; exit 2; }
out=$(realpath -m -- "$1")
seed=$(realpath -- "${TSS_MAVEN_REPOSITORY:?必须指定已准备的 Maven repository 目录}")
[[ -d $seed ]] || exit 2
run="tss-governance-$(date -u +%Y%m%dT%H%M%S)-$$"
mkdir -- "$out"
docker image inspect "${images[@]}" --format '{{.Id}} {{json .RepoTags}}' >"$out/images.txt"
tar -C "$repo" -cf "$out/source.tar" backend/src backend/pom.xml backend/scripts/qa examples
sha256sum "$out/source.tar" >"$out/source.sha256"
since=$(date +%s)
printf '%s\n' "$since" >"$out/started-epoch.txt"
printf '%s\n' "$run" >"$out/run-id.txt"
suites=$(python3 -c 'import json,sys; print(",".join(json.load(open(sys.argv[1]))))' "$script_dir/container-suites.json")
[[ ${2:-} != --all ]] || suites='*Test'

# 源码与缓存复制到本轮容器的 Linux 文件系统；原工作区与原缓存不参与构建写入。
set +e
docker run --rm --pull=never --network host --cpus=2 --memory=2g --memory-swap=2g --pids-limit=512 \
  --label "com.tss.governance.run=$run" \
  --mount type=bind,src=/var/run/docker.sock,dst=/var/run/docker.sock \
  --mount "type=bind,src=$out,dst=/work" --mount "type=bind,src=$seed,dst=/seed,readonly" \
  -e "TSS_GOVERNANCE_TEST_RUN=$run" -e TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 \
  -e DOCKER_HOST=unix:///var/run/docker.sock \
  -e TESTCONTAINERS_PULL_POLICY=com.tss.platform.testsupport.LocalOnlyImagePullPolicy \
  -e "TSS_TEST_SUITES=$suites" -e 'MAVEN_OPTS=-Xms64m -Xmx768m -XX:ActiveProcessorCount=2' \
  -w /qa "$maven_image" bash -c '
    set -Eeuo pipefail
    collect_reports() {
      result=$?
      if [[ -d /qa/source/backend/target/surefire-reports ]]; then
        mkdir -p /work/source/backend/target
        cp -a /qa/source/backend/target/surefire-reports /work/source/backend/target/ || exit 1
      fi
      exit "$result"
    }
    trap collect_reports EXIT
    mkdir source m2
    tar -xf /work/source.tar -C source
    echo "正在复制离线 Maven 缓存到一次性 Linux 构建目录"
    cp -a /seed/. m2/
    cd source/backend
    # 版本由 Maven 的现有 BOM 属性展开；只给测试 JVM 加 agent，不引入查询插件或新依赖。
    mvn --offline --batch-mode --no-transfer-progress -Dmaven.repo.local=/qa/m2 \
      -DargLine="-Xms64m -Xmx768m -XX:ActiveProcessorCount=2 -javaagent:/qa/m2/org/mockito/mockito-core/\${mockito.version}/mockito-core-\${mockito.version}.jar" \
      -DforkCount=1 -DreuseForks=true -Dtest="$TSS_TEST_SUITES" test
  ' 2>&1 | tee "$out/maven.log"
maven_exit=${PIPESTATUS[0]}
set -e
printf '%s\n' "$maven_exit" >"$out/maven-exit.txt"
# 不按名称批量清理。正常生命周期由 Testcontainers 处理，残留必须按本轮标签调查。
docker ps -aq --filter "label=com.tss.governance.run=$run" >"$out/remaining-containers.txt"
[[ ! -s $out/remaining-containers.txt ]] || { echo "本轮容器未清完，见 $out/remaining-containers.txt"; exit 1; }
[[ $maven_exit -eq 0 ]] || exit "$maven_exit"
python3 "$script_dir/verify-integration-reports.py" "$out/source/backend/target/surefire-reports" \
  --since-epoch "$since" | tee "$out/integration-summary.json"
