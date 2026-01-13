#!/usr/bin/env bash
set -euo pipefail

# ====== 設定（ここだけ埋める）======
CF_API_TOKEN="${CF_API_TOKEN:?set CF_API_TOKEN}"
CF_ZONE_ID="${CF_ZONE_ID:?set CF_ZONE_ID}"
HOSTNAME="jenkins-svc.sk4869.info"
# Cloudflare上の複数の custom rule description（配列）
RULE_DESCS=(
  "allowlist-jenkins-svc"
  "allowlist-sonar"
)
STATE_DIR="${STATE_DIR:-$HOME/.cf-allowlist}"
STATE_FILE="$STATE_DIR/prev_ip.txt"
IP_SOURCE_URL="${IP_SOURCE_URL:-https://ifconfig.me}"
# ===================================

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

new_expr="(http.host eq \"$HOSTNAME\" and not ip.src in {$current_ip $prev_ip})"
echo "New expression: $new_expr"

# 1) entrypoint ruleset を取得（http_request_firewall_custom）
entrypoint_json="$(curl -fsS \
  -H "Authorization: Bearer $CF_API_TOKEN" \
  "https://api.cloudflare.com/client/v4/zones/$CF_ZONE_ID/rulesets/phases/http_request_firewall_custom/entrypoint")"

ruleset_id="$(echo "$entrypoint_json" | jq -r '.result.id')"
if [[ -z "$ruleset_id" || "$ruleset_id" == "null" ]]; then
  echo "ERROR: could not get ruleset id" >&2
  exit 3
fi

# 2) 各ルールを更新
updated_count=0
for rule_desc in "${RULE_DESCS[@]}"; do
  echo "Processing rule: $rule_desc"
  
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
  if curl -fsS \
    -X PATCH \
    -H "Authorization: Bearer $CF_API_TOKEN" \
    -H "Content-Type: application/json" \
    --data "$patched_rule" \
    "h✅ Updated $updated_count rule(s)lare.com/client/v4/zones/$CF_ZONE_ID/rulesets/$ruleset_id/rules/$rule_id" >/dev/null; then
    echo "  ✅ Updated: $rule_desc"
    ((updated_count++))
  else
    echo "  ❌ FAILED: $rule_desc" >&2
  fi
done

if [[ $updated_count -eq 0 ]]; then
  echo "ERROR: no rules were updated" >&2
  exit 4
fi

# 5) state更新（前回IPを保存）
echo "$current_ip" > "$STATE_FILE"
echo "Updated allowlist: current=$current_ip prev=$prev_ip"