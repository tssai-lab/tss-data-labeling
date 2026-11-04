#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${script_dir}/lib.sh"

render_manifest() {
  local source_manifest="$1"
  local pod_cidr="$2"

  awk -v pod_cidr="$pod_cidr" '
    function fail(message) {
      print "ERROR: " message > "/dev/stderr"
      exit 42
    }
    $0 == "  calico_backend: \"bird\"" {
      print "  calico_backend: \"vxlan\""
      backend++
      next
    }
    $0 == "              value: \"k8s,bgp\"" {
      print "              value: \"k8s,kubeadm\""
      cluster_type++
      next
    }
    $0 == "            - name: IP" {
      print
      if ((getline next_line) <= 0 || next_line != "              value: \"autodetect\"") {
        fail("unexpected Calico IP autodetection block")
      }
      print next_line
      print "            - name: IP_AUTODETECTION_METHOD"
      print "              value: \"kubernetes-internal-ip\""
      print "            - name: FELIX_NFTABLESMODE"
      print "              value: \"Disabled\""
      print "            - name: FELIX_IPTABLESBACKEND"
      print "              value: \"Legacy\""
      autodetect++
      dataplane++
      next
    }
    $0 == "            - name: CALICO_IPV4POOL_IPIP" {
      if ((getline next_line) <= 0 || next_line != "              value: \"Always\"") {
        fail("unexpected Calico IPIP block")
      }
      ipip++
      next
    }
    $0 == "            - name: CALICO_IPV4POOL_VXLAN" {
      print
      if ((getline next_line) <= 0 || next_line != "              value: \"Never\"") {
        fail("unexpected Calico VXLAN block")
      }
      print "              value: \"Always\""
      vxlan++
      next
    }
    $0 == "            # - name: CALICO_IPV4POOL_CIDR" {
      print "            - name: CALICO_IPV4POOL_CIDR"
      if ((getline next_line) <= 0 || next_line != "            #   value: \"192.168.0.0/16\"") {
        fail("unexpected Calico IPv4 pool block")
      }
      print "              value: \"" pod_cidr "\""
      pool++
      next
    }
    $0 == "                - -bird-live" {
      bird_live++
      next
    }
    $0 == "                - -bird-ready" {
      bird_ready++
      next
    }
    { print }
    END {
      if (backend != 1 || cluster_type != 1 || autodetect != 1 || ipip != 1 \
          || dataplane != 1 || vxlan != 1 || pool != 1 \
          || bird_live != 1 || bird_ready != 1) {
        fail("Calico source structure did not match the pinned manifest")
      }
    }
  ' "$source_manifest"
}

if [[ ${1:-} == --self-test ]]; then
  [[ $# -eq 1 ]] || die "usage: $0 --self-test"
  temporary_dir="$(mktemp -d)"
  trap 'rm -rf -- "$temporary_dir"' EXIT
  cat >"${temporary_dir}/source.yaml" <<'EOF'
data:
  calico_backend: "bird"
            - name: CLUSTER_TYPE
              value: "k8s,bgp"
            - name: IP
              value: "autodetect"
            - name: CALICO_IPV4POOL_IPIP
              value: "Always"
            - name: CALICO_IPV4POOL_VXLAN
              value: "Never"
            # - name: CALICO_IPV4POOL_CIDR
            #   value: "192.168.0.0/16"
                - -bird-live
                - -bird-ready
EOF
  render_manifest "${temporary_dir}/source.yaml" 10.245.0.0/16 \
    >"${temporary_dir}/rendered.yaml"
  grep -F 'calico_backend: "vxlan"' "${temporary_dir}/rendered.yaml" >/dev/null
  grep -F 'value: "k8s,kubeadm"' "${temporary_dir}/rendered.yaml" >/dev/null
  grep -F 'name: IP_AUTODETECTION_METHOD' "${temporary_dir}/rendered.yaml" >/dev/null
  grep -F 'value: "kubernetes-internal-ip"' "${temporary_dir}/rendered.yaml" >/dev/null
  grep -F 'name: FELIX_NFTABLESMODE' "${temporary_dir}/rendered.yaml" >/dev/null
  grep -F 'value: "Disabled"' "${temporary_dir}/rendered.yaml" >/dev/null
  grep -F 'name: FELIX_IPTABLESBACKEND' "${temporary_dir}/rendered.yaml" >/dev/null
  grep -F 'value: "Legacy"' "${temporary_dir}/rendered.yaml" >/dev/null
  grep -F 'value: "10.245.0.0/16"' "${temporary_dir}/rendered.yaml" >/dev/null
  [[ $(grep -Fc 'value: "Always"' "${temporary_dir}/rendered.yaml") -eq 1 ]]
  if grep -F 'CALICO_IPV4POOL_IPIP' "${temporary_dir}/rendered.yaml" >/dev/null \
    || grep -F -- '-bird-' "${temporary_dir}/rendered.yaml" >/dev/null; then
    die "self-test left IPIP or BIRD enabled"
  fi
  echo "Calico VXLAN renderer self-test passed."
  exit 0
fi

config_file="${1:-}"
source_manifest="${2:-}"
[[ -n $config_file && -f $config_file && -n $source_manifest \
  && -f $source_manifest && ! -L $source_manifest && $# -eq 2 ]] \
  || die "usage: $0 /path/to/control.env /path/to/pinned-calico.yaml"
load_internal_config "$config_file"
has_role control-plane || die "Calico rendering must use the control-plane configuration"

manifest_url="https://raw.githubusercontent.com/projectcalico/calico/${TSS_CALICO_VERSION}/manifests/calico.yaml"
expected_checksum="$(awk -v url="$manifest_url" \
  '$1 == "manifest" && $2 == url {sub(/^sha256:/, "", $3); print $3}' \
  "${internal_root}/artifacts.lock")"
[[ $expected_checksum =~ ^[0-9a-f]{64}$ ]] \
  || die "the Calico manifest checksum is absent from artifacts.lock"
actual_checksum="$(sha256sum "$source_manifest" | awk '{print $1}')"
[[ $actual_checksum == "$expected_checksum" ]] \
  || die "Calico manifest checksum does not match artifacts.lock"

render_manifest "$source_manifest" "$TSS_POD_CIDR"
