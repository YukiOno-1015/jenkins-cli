/*
 * Cloudflare WAF の `allowlist` ルールを、指定ホスト群に対して現在の Jenkins 実行ノードの
 * グローバル IP を許可する Declarative Pipeline です。
 *
 * このジョブの役割:
 * - 外向き IP を取得して前回値と比較する
 * - IP 変更時のみ Cloudflare Ruleset の `allowlist` 条件を更新する
 * - 直前 IP も一時的に許可対象へ残し、切り替え時の瞬断を避ける
 * - 対象ホストは `RULE_DEFS` の `hosts` 配列で管理する
 *
 * 必要な Jenkins Credentials（Kind: Secret text）:
 *   CF_API_TOKEN : Cloudflare API Token（Zone.Firewall Services - Edit 権限）
 *   CF_ZONE_ID   : Cloudflare Zone ID
 *
 * もしくは実行環境に `CF_API_TOKEN` / `CF_ZONE_ID` が事前注入されていれば、
 * Jenkins Credentials を使わずその値を優先します。
 *
 * 必要なプラグイン: Pipeline Utility Steps（`readJSON`）
 */

// ---- 更新対象ルール定義 ----------------------------------------
// Cloudflare 側の description と対象 host 群をここで管理する
def RULE_DEFS = [
    [
        desc: 'allowlist',
        hosts: [
            'zabbix-cli.sk4869.info',
            'sonarqube-cli.sk4869.info',
            'jenkins-cli.sk4869.info',
            'pv-svc.sk4869.info',
        ],
    ],
]
// ---------------------------------------------------------------

pipeline {
    agent { label 'machost' }
    triggers { cron('TZ=Asia/Tokyo\nH/10 * * * *') }
    options {
        skipDefaultCheckout(true)
        timestamps()
        disableConcurrentBuilds()
    }

    environment {
        IP_SOURCE_URL = 'https://ifconfig.me'
        STATE_DIR     = "${HOME}/.cf-allowlist"
        STATE_FILE    = "${HOME}/.cf-allowlist/prev_ip.txt"
        CF_API_BASE   = 'https://api.cloudflare.com/client/v4'
    }

    stages {

        stage('Checkout SCM') {
            steps {
                script {
                    int maxAttempts = 3
                    int checkoutTimeoutMinutes = 10
                    int retryWaitSeconds = 15

                    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                        try {
                            timeout(time: checkoutTimeoutMinutes, unit: 'MINUTES') {
                                checkout scm
                            }
                            break
                        } catch (Exception e) {
                            if (attempt == maxAttempts) {
                                throw e
                            }
                            echo "Checkout attempt ${attempt}/${maxAttempts} failed: ${e.getClass().getSimpleName()} - ${e.getMessage()}"
                            echo "Waiting ${retryWaitSeconds}s before retry..."
                            sleep time: retryWaitSeconds, unit: 'SECONDS'
                        }
                    }
                }
                sh 'git log -1 --oneline || true'
            }
        }

        stage('Verify Cloudflare Token') {
            steps {
                script {
                    withCloudflareCredentials {
                        echo '=== Verifying API Token ==='
                        def res = cfGet("${env.CF_API_BASE}/user/tokens/verify")
                        if (res.code != 200) {
                            error("Token verification failed: HTTP ${res.code}\n${res.raw}")
                        }
                        echo "Token status: ${res.body.result?.status}"

                        echo '=== Verifying Zone Access ==='
                        def zoneRes = cfGetByPath('/zones/$CF_ZONE_ID')
                        if (zoneRes.code != 200) {
                            error("Zone access verification failed: HTTP ${zoneRes.code}\n${zoneRes.raw}\nCheck token scopes (Zone.Firewall Services:Edit) and CF_ZONE_ID.")
                        }
                        echo "Zone access OK: ${zoneRes.body.result?.name ?: 'unknown'}"
                    }
                }
            }
        }

        stage('Fetch Current IP') {
            steps {
                script {
                    def ip = sh(
                        script: "curl -fsS '${env.IP_SOURCE_URL}'",
                        returnStdout: true
                    ).trim()

                    if (!(ip ==~ /^(\d{1,3}\.){3}\d{1,3}$/)) {
                        error("Invalid IP address fetched: ${ip}")
                    }
                    env.CURRENT_IP = ip
                    echo "Current IP: ${env.CURRENT_IP}"
                }
            }
        }

        stage('Check IP Change') {
            steps {
                script {
                    sh "mkdir -p '${env.STATE_DIR}'"

                    def prevIp = sh(
                        script: "cat '${env.STATE_FILE}' 2>/dev/null || true",
                        returnStdout: true
                    ).trim()

                    if (env.CURRENT_IP == prevIp) {
                        echo "No change detected (${env.CURRENT_IP}). Skipping update."
                        env.IP_CHANGED = 'false'
                    } else {
                        // 前回 IP が空の場合は current をフォールバックとして使用
                        env.PREV_IP    = prevIp ?: env.CURRENT_IP
                        env.IP_CHANGED = 'true'
                        echo "IP changed: ${env.PREV_IP} -> ${env.CURRENT_IP}"
                    }
                }
            }
        }

        stage('Update Cloudflare Allowlist') {
            when { environment name: 'IP_CHANGED', value: 'true' }
            steps {
                script {
                    withCloudflareCredentials {
                        // 1. エントリポイント ruleset 取得
                        echo '=== Fetching Cloudflare entrypoint ruleset ==='
                        def rulesetRes = cfGetByPath('/zones/$CF_ZONE_ID/rulesets/phases/http_request_firewall_custom/entrypoint')
                        if (rulesetRes.code != 200) {
                            error("Failed to fetch ruleset: HTTP ${rulesetRes.code}\n${rulesetRes.raw}")
                        }
                        def rulesetId = rulesetRes.body.result.id
                        echo "Ruleset ID: ${rulesetId}"

                        // 2. 各ルールを更新
                        int updatedCount = 0
                        int matchedCount = 0
                        for (def ruleDef in RULE_DEFS) {
                            def rule = rulesetRes.body.result.rules.find { it.description == ruleDef.desc }
                            if (!rule) {
                                echo "WARNING: rule not found by description '${ruleDef.desc}' — skipped"
                                continue
                            }
                            matchedCount++

                            def hostValues = ruleDef.hosts ?: (ruleDef.hostname ? [ruleDef.hostname] : [])
                            if (!hostValues) {
                                echo "WARNING: no host configuration found for '${ruleDef.desc}' — skipped"
                                continue
                            }

                            def allowedIps = (ruleDef.allowedIps ?: [env.CURRENT_IP, env.PREV_IP]).findAll { it?.trim() }
                            if (!allowedIps) {
                                echo "WARNING: no allowed IPs configured for '${ruleDef.desc}' — skipped"
                                continue
                            }

                            // hosts に一致した場合のみ IP 制限を適用する（他ドメインへの影響を避ける）
                            def hostExpr = hostValues.collect { "http.host eq \"${it}\"" }.join(' or ')
                            def ipExpr = allowedIps.join(' ')
                            def newExpr = "((${hostExpr}) and not ip.src in {${ipExpr}})"

                            if ((rule.expression ?: '').trim() == newExpr) {
                                echo "Rule already up to date: ${ruleDef.desc}"
                                continue
                            }

                            // 既存ルール定義をベースに expression のみ差し替え
                            def patchMap = [
                                description: rule.description,
                                expression:  newExpr,
                                action:      rule.action,
                                enabled:     rule.enabled,
                            ]
                            if (rule.action_parameters) {
                                patchMap.action_parameters = rule.action_parameters
                            }

                            def patchRes = cfPatch(
                                "${env.CF_API_BASE}/zones/$CF_ZONE_ID/rulesets/${rulesetId}/rules/${rule.id}",
                                patchMap
                            )

                            if (patchRes.code == 200) {
                                echo "Updated rule: ${ruleDef.desc}"
                                updatedCount++
                            } else {
                                def errMsg = patchRes.body?.errors?.getAt(0)?.message ?: "HTTP ${patchRes.code}"
                                error("Failed to update rule '${ruleDef.desc}': ${errMsg}")
                            }
                        }

                        if (matchedCount == 0) {
                            error('No rules were matched — check rule descriptions in Cloudflare')
                        }

                        // 3. 現在 IP をステートに保存
                        sh "echo '${env.CURRENT_IP}' > '${env.STATE_FILE}'"
                        echo "Allowlist sync finished: updated=${updatedCount}, matched=${matchedCount}, current=${env.CURRENT_IP}, prev=${env.PREV_IP}"
                    }
                }
            }
        }
    }

    post {
        success { echo 'Cloudflare allowlist updated successfully' }
        failure { echo 'Cloudflare allowlist update failed. Check console log for details.' }
    }
}

// ============================================================
// HTTP ヘルパー関数（withCredentials スコープ内から呼び出す）
//   CF_API_TOKEN はシェル変数として参照するため Groovy 補間しない
// ============================================================

/** GET リクエスト */
def cfGet(String url) {
    def raw = sh(
        script: """curl -s -w '\\n%{http_code}' \
            -H "Authorization: Bearer \$CF_API_TOKEN" \
            '${url}'""",
        returnStdout: true
    ).trim()
    return parseResponse(raw)
}

/** API パス（例: /zones/$CF_ZONE_ID/...）から GET リクエスト */
def cfGetByPath(String apiPath) {
    def raw = sh(
        script: """curl -s -w '\\n%{http_code}' \\
            -H "Authorization: Bearer \$CF_API_TOKEN" \\
            ${env.CF_API_BASE}${apiPath}""",
        returnStdout: true
    ).trim()
    return parseResponse(raw)
}

/** PATCH リクエスト（Map body を JSON に変換して送信） */
def cfPatch(String url, Map bodyMap) {
    def jsonBody = groovy.json.JsonOutput.toJson(bodyMap)
    def tmpFile  = "${env.WORKSPACE}/.cf_patch_body.json"
    writeFile file: tmpFile, text: jsonBody

    def raw = sh(
        script: """curl -s -w '\\n%{http_code}' \
            -X PATCH \
            -H "Authorization: Bearer \$CF_API_TOKEN" \
            -H 'Content-Type: application/json' \
            --data "@${tmpFile}" \
            '${url}'""",
        returnStdout: true
    ).trim()

    sh "rm -f '${tmpFile}'"
    return parseResponse(raw)
}

/** curl レスポンス（本文 + ステータスコード）をパース */
def parseResponse(String raw) {
    def lines  = raw.split('\n') as List
    def code   = lines.last().toInteger()
    def bodyTxt = lines[0..-2].join('\n')
    def body   = readJSON(text: bodyTxt ?: '{}')
    return [code: code, body: body, raw: bodyTxt]
}

/** Cloudflare credential ID の差分を吸収して実行する */
def withCloudflareCredentials(Closure body) {
    def envToken = env.CF_API_TOKEN?.trim()
    def envZoneId = env.CF_ZONE_ID?.trim()
    if (envToken && envZoneId) {
        echo 'Using pre-injected environment variables for Cloudflare credentials'
        withEnv(["CF_API_TOKEN=${envToken}", "CF_ZONE_ID=${envZoneId}"]) {
            body.call()
        }
        return
    }

    def credentialCandidates = [
        [token: 'CF_API_TOKEN', zone: 'CF_ZONE_ID'],
        [token: 'cf-api-token', zone: 'cf-zone-id'],
    ]
    Exception lastMissingCredentialsError = null

    for (def pair in credentialCandidates) {
        try {
            withCredentials([
                string(credentialsId: pair.token, variable: 'CF_API_TOKEN'),
                string(credentialsId: pair.zone, variable: 'CF_ZONE_ID'),
            ]) {
                echo "Using Cloudflare credentials: token='${pair.token}', zone='${pair.zone}'"
                body.call()
            }
            return
        } catch (Exception e) {
            def message = e.getMessage() ?: ''
            if (message.contains('Could not find credentials entry with ID')) {
                lastMissingCredentialsError = e
                echo "Credential IDs not found, trying next pair..."
                continue
            }
            throw e
        }
    }

    if (lastMissingCredentialsError != null) {
        throw lastMissingCredentialsError
    }
    error('No Cloudflare credential candidates configured')
}