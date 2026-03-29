#!/usr/bin/env bash
set -euo pipefail

assert_contains() {
  local file="$1"
  local pattern="$2"
  local message="$3"

  if ! grep -Eq "$pattern" "$file"; then
    echo "[FAIL] $message"
    echo "  file: $file"
    echo "  expected pattern: $pattern"
    exit 1
  fi

  echo "[PASS] $message"
}

echo "== Basic pipeline contract checks =="

for file in src/*.groovy vars/*.groovy; do
  if [[ ! -s "$file" ]]; then
    echo "[FAIL] File is empty: $file"
    exit 1
  fi
done
echo "[PASS] Groovy files are non-empty"

assert_contains "src/update-proxmox-hosts.groovy" "booleanParam\(name: 'TEST_NOTIFICATIONS_ONLY'" "Proxmox pipeline has TEST_NOTIFICATIONS_ONLY parameter"
assert_contains "src/update-proxmox-hosts.groovy" "booleanParam\(name: 'APPLY_UPDATES'" "Proxmox pipeline has APPLY_UPDATES parameter"
assert_contains "src/update-proxmox-hosts.groovy" "booleanParam\(name: 'ALLOW_REBOOT'" "Proxmox pipeline has ALLOW_REBOOT parameter"
assert_contains "src/update-proxmox-hosts.groovy" "archiveArtifacts artifacts: 'proxmox-host-results.json'" "Proxmox pipeline archives inspection result"

assert_contains "src/update-machosts.groovy" "def TARGET_HOSTS = \[" "Machost pipeline declares target hosts"
assert_contains "src/update-machosts.groovy" "cron\('TZ=Asia/Tokyo" "Machost pipeline uses JST cron"

assert_contains "src/declarative-pipeline.groovy" "def RULE_DEFS = \[" "Cloudflare pipeline defines RULE_DEFS"
assert_contains "src/declarative-pipeline.groovy" "withCloudflareCredentials" "Cloudflare pipeline wraps API calls with credentials helper"

echo "All smoke tests passed."
