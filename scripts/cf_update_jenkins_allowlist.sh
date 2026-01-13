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

# ===== Token verification =====
echo "=== Verifying API Token ==="
token_verify="$(curl -s -w "\n%{http_code}" \
  -H "Authorization: Bearer $CF_API_TOKEN" \
  "https://api.cloudflare.com/client/v4/user/tokens/verify")"

token_http_code="$(echo "$token_verify" | tail -1)"
token_verify_body="$(echo "$token_verify" | sed '$d')"

echo "  Token Status: $token_http_code"
if [[ "$token_http_code" == "200" ]]; then
  token_status="$(echo "$token_verify_body" | jq -r '.result.status // "unknown"')"
  echo "  Token Status: $token_status"
  
  # トークンの全情報を表示（デバッグ用）
  echo "  Full Token Details:"
  echo "$token_verify_body" | jq '.result'
else
  echo "⚠️  WARNING: Could not verify token (HTTP $token_http_code)" >&2
fi
echo ""

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
echo "=== Fetching entrypoint ruleset from Cloudflare ==="
echo "  Zone ID: $CF_ZONE_ID"
echo "  API Token length: ${#CF_API_TOKEN}"
echo "  API Token prefix: ${CF_API_TOKEN:0:10}..."

# トークン形式チェック
if [[ ! "$CF_API_TOKEN" =~ ^[a-zA-Z0-9_-]+$ ]]; then
  echo "⚠️  WARNING: API Token has unexpected characters" >&2
fi

echo "  Request URL: https://api.cloudflare.com/client/v4/zones/$CF_ZONE_ID/rulesets/phases/http_request_firewall_custom/entrypoint"

entrypoint_json="$(curl -s -w "\n%{http_code}" \
  -H "Authorization: Bearer $CF_API_TOKEN" \
  "https://api.cloudflare.com/client/v4/zones/$CF_ZONE_ID/rulesets/phases/http_request_firewall_custom/entrypoint")"

# HTTP ステータスコードを抽出（最後の行）
http_code="$(echo "$entrypoint_json" | tail -1)"
# レスポンスボディ（最後の行を除去）
entrypoint_json="$(echo "$entrypoint_json" | sed '$d')"

echo "  HTTP Status: $http_code"

if [[ "$http_code" != "200" ]]; then
  echo "❌ ERROR: Cloudflare API returned HTTP $http_code" >&2
  echo "Response body:" >&2
  echo "$entrypoint_json" | jq '.' 2>/dev/null || echo "$entrypoint_json" >&2
  
  # 403 の場合は詳細情報
  if [[ "$http_code" == "403" ]]; then
    echo "" >&2
    echo "🔐 Authentication Issue:" >&2
    echo "  1. Check CF_API_TOKEN in Jenkins Credentials" >&2
    echo "  2. Verify token has 'Zone.Firewall Services - Edit' permission" >&2
    echo "  3. Token format should be: 'v1.xxxxxxxxxxxx...'" >&2
  fi
  exit 3
fi

echo "✅ Successfully authenticated with Cloudflare"

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
  update_response="$(curl -s -w "\n%{http_code}" \
    -X PATCH \
    -H "Authorization: Bearer $CF_API_TOKEN" \
    -H "Content-Type: application/json" \
    --data "$patched_rule" \
    "https://api.cloudflare.com/client/v4/zones/$CF_ZONE_ID/rulesets/$ruleset_id/rules/$rule_id")"
  
  # HTTP ステータスコードを抽出（最後の行）
  update_http_code="$(echo "$update_response" | tail -1)"
  # レスポンスボディ（最後の行を除去）
  update_body="$(echo "$update_response" | sed '$d')"
  
  # PATCH レスポンスチェック
  if [[ "$update_http_code" == "200" ]]; then
    echo "  ✅ Updated: $rule_desc"
    ((updated_count++))
  else
    update_error="$(echo "$update_body" | jq -r '.errors[0].message // empty' 2>/dev/null || echo "HTTP $update_http_code")"
    echo "  ❌ FAILED: $rule_desc - $update_error" >&2
    if [[ "$update_http_code" != "200" ]]; then
      echo "    Response: $update_body" >&2
    fi
  fi
done

if [[ $updated_count -eq 0 ]]; then
  echo "ERROR: no rules were updated" >&2
  exit 4
fi

# 5) state更新（前回IPを保存）
echo "$current_ip" > "$STATE_FILE"
echo "✅ Updated $updated_count rule(s): current=$current_ip prev=$prev_ip"