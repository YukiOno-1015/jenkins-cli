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
            'pv-cli.sk4869.info',
        ],
    ],
]
// ---------------------------------------------------------------

pipeline {
    agent {
        kubernetes {
            defaultContainer 'main'
            yaml '''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: main
    image: honoka4869/jenkins-maven-node:latest
    command:
    - cat
    tty: true
'''
        }
    }
    triggers { cron('TZ=Asia/Tokyo\nH/10 * * * *') }
    parameters {
        string(name: 'STATE_FILE_PATH', defaultValue: '', description: '前回IP保存先。空欄時はデフォルト（$HOME/.cf-allowlist/prev_ip.txt）を利用')
    }
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
                echo 'SCM チェックアウトをスキップします（パイプラインソースはSCMから読み込み済み）。'
            }
        }

        stage('Verify Cloudflare Token') {
            steps {
                script {
                    withCloudflareCredentials {
                        echo '=== API トークン検証 ==='
                        def res = cfGet("${env.CF_API_BASE}/user/tokens/verify")
                        if (res.code != 200) {
                            error("トークン検証に失敗しました: HTTP ${res.code}\n${res.raw}")
                        }
                        echo "トークン状態: ${res.body.result?.status}"

                        echo '=== Zone アクセス検証 ==='
                        def zoneRes = cfGetByPath('/zones/$CF_ZONE_ID')
                        if (zoneRes.code != 200) {
                            error("Zone アクセス検証に失敗しました: HTTP ${zoneRes.code}\n${zoneRes.raw}\nトークンスコープ（Zone.Firewall Services:Edit）と CF_ZONE_ID を確認してください。")
                        }
                        echo "Zone アクセス確認OK: ${zoneRes.body.result?.name ?: 'unknown'}"

                        echo '=== Ruleset アクセス検証 ==='
                        def rulesetAccessRes = cfGetByPath('/zones/$CF_ZONE_ID/rulesets/phases/http_request_firewall_custom/entrypoint')
                        if (rulesetAccessRes.code != 200) {
                            error("Ruleset アクセス検証に失敗しました: HTTP ${rulesetAccessRes.code}\n${rulesetAccessRes.raw}\nトークンによる Zone の読み取りはできますが、Ruleset API へのアクセスができません。Zone WAF/Rulesets 権限と zone の include 設定を確認してください。")
                        }
                        echo 'Ruleset アクセス確認OK'
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
                        error("取得した IP アドレスが不正です: ${ip}")
                    }
                    env.CURRENT_IP = ip
                    echo "現在の IP: ${env.CURRENT_IP}"
                }
            }
        }

        stage('Fetch GitHub Webhook IPs') {
            steps {
                script {
                    def raw = sh(
                        script: "curl -fsS 'https://api.github.com/meta'",
                        returnStdout: true
                    ).trim()
                    def meta = readJSON(text: raw)
                    def hookIps = (meta.hooks ?: []) as List
                    if (!hookIps) {
                        error('GitHub Meta API から hooks IP レンジを取得できませんでした。')
                    }
                    env.GITHUB_WEBHOOK_IPS = hookIps.join(' ')
                    echo "GitHub Webhook IP 取得完了: ${hookIps.size()} レンジ"
                }
            }
        }

        stage('Check IP Change') {
            steps {
                script {
                    def stateFile = (params.STATE_FILE_PATH ?: '').trim() ?: env.STATE_FILE
                    def stateDir = sh(script: "dirname '${stateFile}'", returnStdout: true).trim()
                    sh "mkdir -p '${stateDir}'"
                    env.STATE_FILE_EFFECTIVE = stateFile
                    echo "使用する State ファイル: ${env.STATE_FILE_EFFECTIVE}"

                    def prevIp = sh(
                        script: "cat '${env.STATE_FILE_EFFECTIVE}' 2>/dev/null || true",
                        returnStdout: true
                    ).trim()

                    if (env.CURRENT_IP == prevIp) {
                        echo "IP 変更なし（${env.CURRENT_IP}）。更新をスキップします。"
                        env.IP_CHANGED = 'false'
                    } else {
                        // 前回 IP が空の場合は current をフォールバックとして使用
                        env.PREV_IP    = prevIp ?: env.CURRENT_IP
                        env.IP_CHANGED = 'true'
                        echo "IP が変更されました: ${env.PREV_IP} → ${env.CURRENT_IP}"
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
                        echo '=== Cloudflare エントリポイント Ruleset 取得 ==='
                        def rulesetRes = cfGetByPath('/zones/$CF_ZONE_ID/rulesets/phases/http_request_firewall_custom/entrypoint')
                        if (rulesetRes.code != 200) {
                            error("Ruleset の取得に失敗しました: HTTP ${rulesetRes.code}\n${rulesetRes.raw}")
                        }
                        def rulesetId = rulesetRes.body.result.id
                        echo "Ruleset ID: ${rulesetId}"

                        // 2. 各ルールを更新
                        int updatedCount = 0
                        int matchedCount = 0
                        for (def ruleDef in RULE_DEFS) {
                            def rule = rulesetRes.body.result.rules.find { it.description == ruleDef.desc }
                            if (!rule) {
                                echo "警告: 説明 '${ruleDef.desc}' に一致するルールが見つかりません。スキップします。"
                                continue
                            }
                            matchedCount++

                            def hostValues = ruleDef.hosts ?: (ruleDef.hostname ? [ruleDef.hostname] : [])
                            if (!hostValues) {
                                echo "警告: '${ruleDef.desc}' に対するホスト設定が見つかりません。スキップします。"
                                continue
                            }

                            def githubIps = (env.GITHUB_WEBHOOK_IPS ?: '').trim().tokenize()
                            def baseIps = ruleDef.allowedIps ?: [env.CURRENT_IP, env.PREV_IP]
                            def allowedIps = (baseIps + githubIps).findAll { it?.trim() }.unique()
                            if (!allowedIps) {
                                echo "警告: '${ruleDef.desc}' に許可 IP が設定されていません。スキップします。"
                                continue
                            }

                            // hosts に一致した場合のみ IP 制限を適用する（他ドメインへの影響を避ける）
                            def hostExpr = hostValues.collect { "http.host eq \"${it}\"" }.join(' or ')
                            def ipExpr = allowedIps.join(' ')
                            def newExpr = "((${hostExpr}) and not ip.src in {${ipExpr}})"

                            if ((rule.expression ?: '').trim() == newExpr) {
                                echo "ルールは最新です（更新不要）: ${ruleDef.desc}"
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

                            def patchRes = cfPatchByPath(
                                "/zones/$CF_ZONE_ID/rulesets/${rulesetId}/rules/${rule.id}",
                                patchMap
                            )

                            if (patchRes.code == 200) {
                                echo "ルールを更新しました: ${ruleDef.desc}"
                                updatedCount++
                            } else {
                                def errMsg = patchRes.body?.errors?.getAt(0)?.message ?: "HTTP ${patchRes.code}"
                                error("ルール '${ruleDef.desc}' の更新に失敗しました: ${errMsg}")
                            }
                        }

                        if (matchedCount == 0) {
                            error('一致するルールが見つかりませんでした。Cloudflare のルール説明を確認してください。')
                        }

                        // 3. 現在 IP をステートに保存
                        def stateFile = (env.STATE_FILE_EFFECTIVE ?: env.STATE_FILE).trim()
                        sh "echo '${env.CURRENT_IP}' > '${stateFile}'"
                        echo "許可リスト同期完了: 更新=${updatedCount}, 一致=${matchedCount}, 現在IP=${env.CURRENT_IP}, 前回IP=${env.PREV_IP}"
                    }
                }
            }
        }
    }

    post {
        success { echo 'Cloudflare 許可リストの更新が正常に完了しました。' }
        failure { echo 'Cloudflare 許可リストの更新が失敗しました。コンソールログを確認してください。' }
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
    def raw
    withEnv(["CF_API_PATH_TEMPLATE=${apiPath}"]) {
        raw = sh(
            script: '''
CF_API_PATH="$(printf '%s' "$CF_API_PATH_TEMPLATE" | awk -v z="$CF_ZONE_ID" '{gsub(/\\$CF_ZONE_ID/, z); print}')"
curl -s -w "\\n%{http_code}" -H "Authorization: Bearer $CF_API_TOKEN" "$CF_API_BASE$CF_API_PATH"
''',
            returnStdout: true
        ).trim()
    }
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

/** API パス（例: /zones/$CF_ZONE_ID/...）へ PATCH リクエスト */
def cfPatchByPath(String apiPath, Map bodyMap) {
    def jsonBody = groovy.json.JsonOutput.toJson(bodyMap)
    def tmpFile  = "${env.WORKSPACE}/.cf_patch_body.json"
    writeFile file: tmpFile, text: jsonBody

    def raw
    withEnv(["CF_API_PATH_TEMPLATE=${apiPath}", "CF_TMP_FILE=${tmpFile}"]) {
        raw = sh(
            script: '''
CF_API_PATH="$(printf '%s' "$CF_API_PATH_TEMPLATE" | awk -v z="$CF_ZONE_ID" '{gsub(/\\$CF_ZONE_ID/, z); print}')"
curl -s -w "\\n%{http_code}" -X PATCH -H "Authorization: Bearer $CF_API_TOKEN" -H "Content-Type: application/json" --data "@$CF_TMP_FILE" "$CF_API_BASE$CF_API_PATH"
''',
            returnStdout: true
        ).trim()
    }

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
        echo 'Cloudflare 認証情報を環境変数から直接取得します。'
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
                echo "Cloudflare 認証情報を使用: token='${pair.token}', zone='${pair.zone}'"
                body.call()
            }
            return
        } catch (Exception e) {
            def message = e.getMessage() ?: ''
            if (message.contains('Could not find credentials entry with ID')) {
                lastMissingCredentialsError = e
                echo '策定 Credential ID が見つかりません。次の候補を試みます…'
                continue
            }
            throw e
        }
    }

    if (lastMissingCredentialsError != null) {
        throw lastMissingCredentialsError
    }
    error('Cloudflare 認証情報の候補が設定されていません。')
}