#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${script_dir}/lib.sh"

usage() {
  echo "usage: $0 --config-only|--check /path/to/storage.env" >&2
  echo "       $0 --apply /path/to/storage.env --confirm-serial SERIAL" >&2
  exit 2
}

mode="${1:-}"
config_file="${2:-}"
confirmation_flag="${3:-}"
confirmation_serial="${4:-}"
[[ $mode == --config-only || $mode == --check || $mode == --apply ]] || usage
[[ -n $config_file && -f $config_file ]] || usage
if [[ $mode == --apply ]]; then
  [[ $confirmation_flag == --confirm-serial && -n $confirmation_serial ]] || usage
else
  [[ -z $confirmation_flag && -z $confirmation_serial ]] || usage
fi

set -a
# The reviewed storage file contains hardware identity only, never credentials.
# shellcheck disable=SC1090
source "$config_file"
set +a

for setting in \
  TSS_STORAGE_DEVICE_BY_ID TSS_STORAGE_EXPECTED_MODEL \
  TSS_STORAGE_EXPECTED_SERIAL TSS_STORAGE_EXPECTED_WWN \
  TSS_STORAGE_EXPECTED_BYTES TSS_STORAGE_SMART_POLICY \
  TSS_STORAGE_PARTITION_GIB \
  TSS_STORAGE_FS_LABEL TSS_STORAGE_MOUNT_POINT; do
  require_var "$setting"
done

[[ $TSS_STORAGE_DEVICE_BY_ID =~ ^/dev/disk/by-id/ata-[A-Za-z0-9._+-]+$ ]] \
  || die "storage device must be an ATA /dev/disk/by-id path"
[[ $TSS_STORAGE_EXPECTED_MODEL =~ ^[A-Za-z0-9._+-]+$ ]] \
  || die "expected model contains invalid characters"
[[ $TSS_STORAGE_EXPECTED_SERIAL =~ ^[A-Za-z0-9._+-]+$ ]] \
  || die "expected serial contains invalid characters"
[[ $TSS_STORAGE_EXPECTED_WWN =~ ^0x[0-9a-f]{16}$ ]] \
  || die "expected WWN must be a lowercase 64-bit hexadecimal identifier"
[[ $TSS_STORAGE_EXPECTED_BYTES =~ ^[1-9][0-9]{12,}$ ]] \
  || die "expected byte capacity must be an integer"
[[ $TSS_STORAGE_SMART_POLICY == extended \
  || $TSS_STORAGE_SMART_POLICY == short-plus-critical-attributes ]] \
  || die "SMART policy must be extended or short-plus-critical-attributes"
[[ $TSS_STORAGE_PARTITION_GIB == 2048 ]] \
  || die "this approval is limited to one 2048 GiB partition"
[[ $TSS_STORAGE_FS_LABEL == tss-AIplatform ]] \
  || die "filesystem label must be tss-AIplatform"
validate_path TSS_STORAGE_MOUNT_POINT
[[ ${TSS_STORAGE_MOUNT_POINT##*/} == tss-AIplatform ]] \
  || die "mount point must end with the dedicated tss-AIplatform directory"
case "$TSS_STORAGE_MOUNT_POINT" in
  /boot/*|/dev/*|/etc/*|/proc/*|/run/*|/sys/*|/usr/*|/var/*)
    die "mount point must not be below an operating-system directory"
    ;;
esac

if [[ $mode == --config-only ]]; then
  echo "Storage configuration contract passed: serial=${TSS_STORAGE_EXPECTED_SERIAL} size=2048GiB"
  exit 0
fi

[[ $EUID -eq 0 ]] || die "storage inspection and application must run as root"
for command_name in \
  awk basename blkid blockdev find findmnt flock grep install lsblk mkfs.ext4 \
  mount mountpoint partprobe readlink sgdisk sleep smartctl swapon tr udevadm \
  wipefs; do
  command -v "$command_name" >/dev/null 2>&1 \
    || die "required command is missing: $command_name"
done

if [[ $mode == --apply ]]; then
  exec 9>/run/lock/tss-aiplatform-storage.lock
  flock -n 9 || die "another storage operation is already running"
fi

resolved_device="$(readlink -f -- "$TSS_STORAGE_DEVICE_BY_ID")"
[[ -b $resolved_device ]] || die "reviewed by-id path is not a block device"
[[ $(lsblk -dn -o TYPE -- "$resolved_device") == disk ]] \
  || die "reviewed by-id path does not resolve to a whole disk"
[[ $(blockdev --getro "$resolved_device") == 0 ]] \
  || die "reviewed disk is read-only"

udev_properties="$(udevadm info --query=property --name="$resolved_device")"
actual_model="$(awk -F= '$1 == "ID_MODEL" {print $2}' <<<"$udev_properties")"
actual_serial="$(awk -F= '$1 == "ID_SERIAL_SHORT" {print $2}' <<<"$udev_properties")"
actual_wwn="$(awk -F= '$1 == "ID_WWN" {print $2}' <<<"$udev_properties")"
actual_bytes="$(blockdev --getsize64 "$resolved_device")"

[[ $actual_model == "$TSS_STORAGE_EXPECTED_MODEL" ]] \
  || die "disk model mismatch: expected=$TSS_STORAGE_EXPECTED_MODEL actual=$actual_model"
[[ $actual_serial == "$TSS_STORAGE_EXPECTED_SERIAL" ]] \
  || die "disk serial mismatch: expected=$TSS_STORAGE_EXPECTED_SERIAL actual=$actual_serial"
[[ $actual_wwn == "$TSS_STORAGE_EXPECTED_WWN" ]] \
  || die "disk WWN mismatch: expected=$TSS_STORAGE_EXPECTED_WWN actual=$actual_wwn"
[[ $actual_bytes == "$TSS_STORAGE_EXPECTED_BYTES" ]] \
  || die "disk capacity mismatch: expected=$TSS_STORAGE_EXPECTED_BYTES actual=$actual_bytes"

mapfile -t disk_nodes < <(lsblk -nrpo NAME -- "$resolved_device")
[[ ${#disk_nodes[@]} -eq 1 && ${disk_nodes[0]} == "$resolved_device" ]] \
  || die "disk already has child partitions or device-mapper users"
[[ -z $(lsblk -nrpo MOUNTPOINTS -- "$resolved_device" | tr -d '[:space:]') ]] \
  || die "disk or a child device is mounted"
if findmnt -rn -S "$resolved_device" >/dev/null 2>&1; then
  die "disk is already a mounted source"
fi
if swapon --noheadings --raw --show=NAME 2>/dev/null | grep -Fx "$resolved_device" >/dev/null; then
  die "disk is active swap"
fi
device_name="$(basename "$resolved_device")"
if [[ -d /sys/class/block/${device_name}/holders ]] \
  && find "/sys/class/block/${device_name}/holders" -mindepth 1 -print -quit | grep -q .; then
  die "disk has active kernel holders"
fi

wipefs_output="$(wipefs -n -- "$resolved_device")"
[[ -z $wipefs_output ]] || die "disk contains a filesystem or partition signature"
blkid_output="$(blkid -p -o export -- "$resolved_device" 2>/dev/null || true)"
[[ -z $blkid_output ]] || die "disk contains data recognized by blkid"

smart_health="$(smartctl -H -- "$resolved_device")"
grep -F 'SMART overall-health self-assessment test result: PASSED' \
  <<<"$smart_health" >/dev/null || die "SMART overall health is not PASSED"
self_test_capabilities="$(smartctl -c -- "$resolved_device")"
if grep -F 'Self-test routine in progress' <<<"$self_test_capabilities" >/dev/null; then
  die "a SMART self-test is still running; stop or complete it before storage changes"
fi
self_test_log="$(smartctl -l selftest -- "$resolved_device")"
if [[ $TSS_STORAGE_SMART_POLICY == extended ]]; then
  latest_self_test="$(awk '/^# *[0-9]+/ {print; exit}' <<<"$self_test_log")"
  [[ $latest_self_test == *'Extended offline'* \
    && $latest_self_test == *'Completed without error'* ]] \
    || die "latest SMART test is not a successful extended test: $latest_self_test"
else
  latest_short_test="$(awk '/^# *[0-9]+ +Short offline/ {print; exit}' \
    <<<"$self_test_log")"
  [[ $latest_short_test == *'Completed without error'* ]] \
    || die "no successful SMART short test was found: $latest_short_test"

  smart_attributes="$(smartctl -A -- "$resolved_device")"
  for attribute_id in 5 187 188 197 198 199; do
    attribute_line="$(awk -v id="$attribute_id" '$1 == id {print; exit}' \
      <<<"$smart_attributes")"
    [[ -n $attribute_line ]] \
      || die "required SMART attribute is missing: id=$attribute_id"
    attribute_raw="$(awk '{print $10}' <<<"$attribute_line")"
    [[ $attribute_raw =~ ^0+$ ]] \
      || die "critical SMART attribute is nonzero: id=$attribute_id raw=$attribute_raw"
  done
fi

echo "PASS: exact empty disk identity rechecked"
echo "PASS: SMART policy passed: ${TSS_STORAGE_SMART_POLICY}"
echo "PLAN: create one ${TSS_STORAGE_PARTITION_GIB}GiB ext4 partition"
echo "PLAN: mount by filesystem UUID at ${TSS_STORAGE_MOUNT_POINT}"
echo "PLAN: leave the remaining disk capacity unallocated"

if [[ $mode == --check ]]; then
  echo "Storage check passed without writes: device=${TSS_STORAGE_DEVICE_BY_ID}"
  exit 0
fi

[[ $confirmation_serial == "$TSS_STORAGE_EXPECTED_SERIAL" ]] \
  || die "confirmation serial does not match the reviewed disk"

apply_phase=partition-table
on_apply_error() {
  local status=$?
  echo "ERROR: storage apply failed during phase=${apply_phase}; preserve the host for inspection" >&2
  logger -t tss-aiplatform-storage \
    "apply failed phase=${apply_phase} device=${TSS_STORAGE_DEVICE_BY_ID} status=${status}" \
    2>/dev/null || true
  exit "$status"
}
trap on_apply_error ERR

logger -t tss-aiplatform-storage \
  "approved apply start device=${TSS_STORAGE_DEVICE_BY_ID} serial=${actual_serial} partition_gib=${TSS_STORAGE_PARTITION_GIB}" \
  2>/dev/null || true
sgdisk --clear \
  --new="1:2048:+${TSS_STORAGE_PARTITION_GIB}G" \
  --typecode=1:8300 \
  --change-name=1:tss-AIplatform \
  -- "$resolved_device"
sgdisk --verify -- "$resolved_device"
partprobe "$resolved_device"
udevadm settle --timeout=30

apply_phase=partition-identity
partition_by_id="${TSS_STORAGE_DEVICE_BY_ID}-part1"
for _ in {1..30}; do
  [[ -b $partition_by_id ]] && break
  sleep 1
done
[[ -b $partition_by_id ]] || die "reviewed partition by-id path did not appear"
resolved_partition="$(readlink -f -- "$partition_by_id")"
mapfile -t applied_disk_nodes < <(lsblk -nrpo NAME -- "$resolved_device")
[[ ${#applied_disk_nodes[@]} -eq 2 \
  && ${applied_disk_nodes[0]} == "$resolved_device" \
  && ${applied_disk_nodes[1]} == "$resolved_partition" ]] \
  || die "partition table does not contain exactly the approved partition"
[[ $(lsblk -dn -o PKNAME -- "$resolved_partition") == "$device_name" ]] \
  || die "partition parent is not the reviewed disk"
expected_partition_bytes=$((TSS_STORAGE_PARTITION_GIB * 1024 * 1024 * 1024))
actual_partition_bytes="$(blockdev --getsize64 "$resolved_partition")"
partition_delta=$((actual_partition_bytes - expected_partition_bytes))
(( partition_delta < 0 )) && partition_delta=$((-partition_delta))
(( partition_delta <= 1048576 )) \
  || die "partition size differs from the approved size by more than 1 MiB"

apply_phase=filesystem
mkfs.ext4 -F -m 0 -L "$TSS_STORAGE_FS_LABEL" -- "$resolved_partition"
filesystem_uuid="$(blkid -s UUID -o value -- "$resolved_partition")"
[[ $filesystem_uuid =~ ^[0-9A-Fa-f-]{36}$ ]] \
  || die "new ext4 filesystem UUID is invalid"

apply_phase=fstab
[[ ! -L $TSS_STORAGE_MOUNT_POINT ]] || die "mount point is a symbolic link"
if [[ -e $TSS_STORAGE_MOUNT_POINT && ! -d $TSS_STORAGE_MOUNT_POINT ]]; then
  die "mount point exists and is not a directory"
fi
if [[ -d $TSS_STORAGE_MOUNT_POINT ]] \
  && find "$TSS_STORAGE_MOUNT_POINT" -mindepth 1 -print -quit | grep -q .; then
  die "mount point directory is not empty"
fi
if awk -v target="$TSS_STORAGE_MOUNT_POINT" '$2 == target {found = 1} END {exit !found}' \
  /etc/fstab; then
  die "fstab already contains the project mount point"
fi
install -d -m 0755 -o root -g root "$TSS_STORAGE_MOUNT_POINT"
fstab_backup="/etc/fstab.tss-aiplatform-before-${filesystem_uuid}"
install -m 0644 -o root -g root /etc/fstab "$fstab_backup"
printf 'UUID=%s %s ext4 defaults,nofail,x-systemd.device-timeout=30s 0 2\n' \
  "$filesystem_uuid" "$TSS_STORAGE_MOUNT_POINT" >>/etc/fstab
if ! findmnt --verify --tab-file /etc/fstab >/dev/null; then
  install -m 0644 -o root -g root "$fstab_backup" /etc/fstab
  die "fstab verification failed; the original file was restored"
fi

apply_phase=mount
if ! mount "$TSS_STORAGE_MOUNT_POINT"; then
  install -m 0644 -o root -g root "$fstab_backup" /etc/fstab
  die "new filesystem mount failed; the original fstab was restored"
fi
mountpoint -q "$TSS_STORAGE_MOUNT_POINT" \
  || die "new filesystem did not mount"
[[ $(findmnt -rn -M "$TSS_STORAGE_MOUNT_POINT" -o UUID) == "$filesystem_uuid" ]] \
  || die "mounted filesystem UUID differs from the formatted partition"

trap - ERR
logger -t tss-aiplatform-storage \
  "apply complete uuid=${filesystem_uuid} mount=${TSS_STORAGE_MOUNT_POINT}" \
  2>/dev/null || true
echo "Storage apply complete: uuid=${filesystem_uuid} mount=${TSS_STORAGE_MOUNT_POINT}"
