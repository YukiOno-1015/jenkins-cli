#!/usr/bin/env bash
set -euo pipefail

# ====== 設定（ここだけ埋める）======
CF_API_TOKEN="${CF_API_TOKEN:?set CF_API_TOKEN}"
CF_ZONE_ID="${CF_ZONE_ID:?set CF_ZONE_ID}"

# Cloudflare上の複数の custom rule description（配列）
# RULE_DESCS と HOSTNAMES は同じインデックスで対応
RULE_DESCS=(
  "allowlist-jenkins-svc"
  "allowlist-sonar"
)
HOSTNAMES=(
  "jenkins-svc.sk4869.info"
  "sonar.sk4869.info"
)

STATE_DIR="${STATE_DIR:-$HOME/.cf-allowlist}"
STATE_FILE="$STATE_DIR/prev_ip.txt"
IP_SOURCE_URL="${IP_SOURCE_URL:-https://ifconfig.me}"
# ===================================

# 配列の長さチェック
if [[ ${#RULE_DESCS[@]} -ne ${#HOSTNAMES[@]} ]]; then
  echo "ERROR: RULE_DESCS and HOSTNAMES must have the same length" >&2
  exit 1
fi

mkdir -p "$STATE_DIR"

current_ip="$(curl -fsS "$IP_SOURCE_URL" | tr -d '[:space:]')"

# IPv4の雑チェック
if ! [[ "$current_ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
  echo "ERROR: invalid IP: $current_ip" >&2
  exit 2
fi

prev_ip=""
if [[ -f "$STATE_FILE" ]]; then
  prev_ip="$(cat "$STATE_FILE" | tr -d '[:space:]' || true)"
fi

# 変化なしなら何もしない
if [[ "$current_ip" == "$prev_ip" ]]; then
  echo "No change: $current_ip"
  exit 0
fi

# 前回IPが空なら、とりあえず current を2回入れて事故回避
if [[ -z "$prev_ip" ]]; then
  prev_ip="$current_ip"
fi

# 1) entrypoint ruleset を取得（http_request_firewall_custom）
echo "Fetching entrypoint ruleset from Cloudflare..."
entrypoint_json="$(curl -fsS \
  -H "Authorization: Bearer $CF_API_TOKEN" \
  "https://api.cloudflare.com/client/v4/zones/$CF_ZONE_ID/rulesets/phases/http_request_firewall_custom/entrypoint")"

# エラーチェック
if ! echo "$entrypoint_json" | jq empty 2>/dev/null; then
  echo "ERROR: Invalid JSON response from Cloudflare API" >&2
  echo "Response: $entrypoint_json" >&2
  exit 3
fi

# API エラーチェック
api_error="$(echo "$entrypoint_json" | jq -r '.errors[0].message // empty')"
if [[ -n "$api_error" ]]; then
  echo "ERROR: Cloudflare API error: $api_error" >&2
  exit 3
fi

ruleset_id="$(echo "$entrypoint_json" | jq -r '.result.id')"
if [[ -z "$ruleset_id" || "$ruleset_id" == "null" ]]; then
  echo "ERROR: could not get ruleset id" >&2
  echo "Full response: $entrypoint_json" >&2
  exit 3
fi

echo "✅ Fetched ruleset ID: $ruleset_id"

# 2) 各ルールを更新
updated_count=0
for i in "${!RULE_DESCS[@]}"; do
  rule_desc="${RULE_DESCS[$i]}"
  hostname="${HOSTNAMES[$i]}"
  
  echo "Processing rule: $rule_desc (hostname: $hostname)"
  
  # 各ルール用の expression を生成
  new_expr="(http.host eq \"$hostname\" and not ip.src in {$current_ip $prev_ip})"
  
  # 対象ルールを description で探す
  rule_json="$(echo "$entrypoint_json" | jq -c --arg d "$rule_desc" '.result.rules[] | select(.description==$d)')"
  if [[ -z "$rule_json" ]]; then
    echo "  ⚠️  SKIP: rule not found by description: $rule_desc" >&2
    continue
  fi

  rule_id="$(echo "$rule_json" | jq -r '.id')"

  # 3) PATCHは「必要なフィールドを含めて更新」が原則なので、既存定義をベースに expression だけ差し替える
  patched_rule="$(echo "$rule_json" | jq --arg e "$new_expr" '
    {
      description,
      expression: $e,
      action,
      enabled,
      action_parameters
    }
    # nullは送らない（Cloudflare側で不要フィールド扱い）
    | with_entries(select(.value != null))
  ')"

  # 4) 更新
  update_response="$(curl -fsS \
    -X PATCH \
    -H "Authorization: Bearer $CF_API_TOKEN" \
    -H "Content-Type: application/json" \
    --data "$patched_rule" \
    "https://api.cloudflare.com/client/v4/zones/$CF_ZONE_ID/rulesets/$ruleset_id/rules/$rule_id" 2>&1 || true)"
  
  # PATCH レスポンスチェック
  if echo "$update_response" | jq empty 2>/dev/null; then
    update_error="$(echo "$update_response" | jq -r '.errors[0].message // empty')"
    if [[ -n "$update_error" ]]; then
      echo "  ❌ FAILED: $rule_desc - API error: $update_error" >&2
    else
      echo "  ✅ Updated: $rule_desc"
      ((updated_count++))
    fi
  else
    echo "  ❌ FAILED: $rule_desc - Invalid response: $update_response" >&2
  fi
done

if [[ $updated_count -eq 0 ]]; then
  echo "ERROR: no rules were updated" >&2
  exit 4
fi

# 5) state更新（前回IPを保存）
echo "$current_ip" > "$STATE_FILE"
echo "✅ Updated $updated_count rule(s): current=$current_ip prev=$prev_ip"