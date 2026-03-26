// Cloudflare WAF allowlist を現在の Jenkins ノード IP で更新する Declarative Pipeline
//
// 必要な Jenkins Credentials（Kind: Secret text）:
//   CF_API_TOKEN : Cloudflare API Token（Zone.Firewall Services - Edit 権限）
//   CF_ZONE_ID   : Cloudflare Zone ID
//
// 必要なプラグイン: Pipeline Utility Steps（readJSON）

// ---- 更新対象ルール定義 ----------------------------------------
// desc と hostname は 1:1 で対応させること
def RULE_DEFS = [
    [desc: 'allowlist-jenkins-svc', hostname: 'jenkins-cli.sk4869.info'],
    [desc: 'allowlist-sonar',       hostname: 'sonar-cli.sk4869.info'],
]
// ---------------------------------------------------------------

pipeline {
    agent { label 'machost' }
    triggers { cron('H/10 * * * *') }

    environment {
        IP_SOURCE_URL = 'https://ifconfig.me'
        STATE_DIR     = "${HOME}/.cf-allowlist"
        STATE_FILE    = "${HOME}/.cf-allowlist/prev_ip.txt"
        CF_API_BASE   = 'https://api.cloudflare.com/client/v4'
    }

    stages {

        stage('Pull Latest Changes') {
            steps {
                sh '''
                    git pull origin main
                    git log -1 --oneline
                '''
            }
        }

        stage('Verify Cloudflare Token') {
            steps {
                withCredentials([
                    string(credentialsId: 'CF_API_TOKEN', variable: 'CF_API_TOKEN'),
                    string(credentialsId: 'CF_ZONE_ID',   variable: 'CF_ZONE_ID'),
                ]) {
                    script {
                        echo '=== Verifying API Token ==='
                        def res = cfGet("${env.CF_API_BASE}/user/tokens/verify")
                        if (res.code != 200) {
                            error("Token verification failed: HTTP ${res.code}\n${res.raw}")
                        }
                        echo "Token status: ${res.body.result?.status}"
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
                withCredentials([
                    string(credentialsId: 'CF_API_TOKEN', variable: 'CF_API_TOKEN'),
                    string(credentialsId: 'CF_ZONE_ID',   variable: 'CF_ZONE_ID'),
                ]) {
                    script {
                        // 1. エントリポイント ruleset 取得
                        echo '=== Fetching Cloudflare entrypoint ruleset ==='
                        def rulesetRes = cfGet(
                            "${env.CF_API_BASE}/zones/${env.CF_ZONE_ID}/rulesets/phases/http_request_firewall_custom/entrypoint"
                        )
                        if (rulesetRes.code != 200) {
                            error("Failed to fetch ruleset: HTTP ${rulesetRes.code}\n${rulesetRes.raw}")
                        }
                        def rulesetId = rulesetRes.body.result.id
                        echo "Ruleset ID: ${rulesetId}"

                        // 2. 各ルールを更新
                        int updatedCount = 0
                        for (def ruleDef in RULE_DEFS) {
                            def rule = rulesetRes.body.result.rules.find { it.description == ruleDef.desc }
                            if (!rule) {
                                echo "WARNING: rule not found by description '${ruleDef.desc}' — skipped"
                                continue
                            }

                            def newExpr = "(http.host eq \"${ruleDef.hostname}\" and not ip.src in {${env.CURRENT_IP} ${env.PREV_IP}})"

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
                                "${env.CF_API_BASE}/zones/${env.CF_ZONE_ID}/rulesets/${rulesetId}/rules/${rule.id}",
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

                        if (updatedCount == 0) {
                            error('No rules were updated — check rule descriptions in Cloudflare')
                        }

                        // 3. 現在 IP をステートに保存
                        sh "echo '${env.CURRENT_IP}' > '${env.STATE_FILE}'"
                        echo "Updated ${updatedCount} rule(s): current=${env.CURRENT_IP} prev=${env.PREV_IP}"
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
            -H 'Authorization: Bearer \$CF_API_TOKEN' \
            '${url}'""",
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
            -H 'Authorization: Bearer \$CF_API_TOKEN' \
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