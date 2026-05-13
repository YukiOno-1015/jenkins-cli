/*
 * Cloudflare Email Routing に「カスタムアドレス → 転送先」ルールを追加する Declarative Pipeline です。
 *
 * このジョブの役割:
 * - 転送先 (destination) を登録する（既存ならスキップ。新規は Cloudflare から検証メールが届く）
 * - カスタムアドレス (custom address: alias@your-zone) を `to` 一致で転送するルールを作成する
 * - 同名/同マッチャのルールが既に存在する場合は冪等にスキップ or 更新する
 *
 * 必要な Jenkins Credentials（Kind: Secret text）:
 *   CF_API_TOKEN  : Cloudflare API Token
 *                   必要権限: Zone > Email Routing Rules > Edit
 *                             Account > Email Routing Addresses > Edit（転送先を新規登録する場合）
 *                             Zone > Zone > Read（account_id 自動解決用）
 *   CF_ZONE_ID    : Cloudflare Zone ID（カスタムアドレスのドメインに対応する Zone）
 *   CF_ACCOUNT_ID : 任意。指定が無ければ Zone から自動解決する
 *
 * 実行環境に CF_API_TOKEN / CF_ZONE_ID（および任意で CF_ACCOUNT_ID）が事前注入されていれば
 * Jenkins Credentials を使わずその値を優先します。
 *
 * 必要なプラグイン: Pipeline Utility Steps（`readJSON`）
 */

pipeline {
    agent {
        kubernetes {
            defaultContainer 'main'
            yaml '''
apiVersion: v1
kind: Pod
spec:
  imagePullSecrets:
  - name: nexus
  containers:
  - name: main
    image: nexus-docker-pull.sk4869.info/honoka4869/jenkins-maven-node:latest
    command:
    - cat
    tty: true
'''
        }
    }
    parameters {
        string(name: 'CUSTOM_ADDRESS',      defaultValue: '', description: '追加するカスタムアドレス（例: alias@your-zone.example.com）')
        string(name: 'DESTINATION_ADDRESS', defaultValue: '', description: '転送先メールアドレス（例: real@gmail.com）。未登録ならアカウントへ登録要求も実施')
        string(name: 'RULE_NAME',           defaultValue: '', description: 'ルール名。空欄なら "forward <custom> -> <destination>" を自動採番')
        booleanParam(name: 'RULE_ENABLED',  defaultValue: true, description: '作成するルールを有効化するか')
    }
    options {
        skipDefaultCheckout(true)
        timestamps()
        disableConcurrentBuilds()
    }

    environment {
        CF_API_BASE = 'https://api.cloudflare.com/client/v4'
    }

    stages {

        stage('Validate Parameters') {
            steps {
                script {
                    def custom = (params.CUSTOM_ADDRESS ?: '').trim()
                    def dest   = (params.DESTINATION_ADDRESS ?: '').trim()
                    def emailRe = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

                    if (!(custom ==~ emailRe)) {
                        error("CUSTOM_ADDRESS が不正です: '${custom}'")
                    }
                    if (!(dest ==~ emailRe)) {
                        error("DESTINATION_ADDRESS が不正です: '${dest}'")
                    }

                    env.CUSTOM_ADDR = custom
                    env.DEST_ADDR   = dest
                    env.RULE_NAME_EFFECTIVE = (params.RULE_NAME ?: '').trim() ?: "forward ${custom} -> ${dest}"
                    echo "カスタムアドレス : ${env.CUSTOM_ADDR}"
                    echo "転送先          : ${env.DEST_ADDR}"
                    echo "ルール名        : ${env.RULE_NAME_EFFECTIVE}"
                }
            }
        }

        stage('Verify Cloudflare Token & Resolve Account') {
            steps {
                script {
                    withCloudflareCredentials {
                        echo '=== API トークン検証 ==='
                        def tokRes = cfGet("${env.CF_API_BASE}/user/tokens/verify")
                        if (tokRes.code != 200) {
                            error("トークン検証に失敗しました: HTTP ${tokRes.code}\n${tokRes.raw}")
                        }
                        echo "トークン状態: ${tokRes.body.result?.status}"

                        echo '=== Zone アクセス検証 / Account ID 解決 ==='
                        def zoneRes = cfGetByPath('/zones/$CF_ZONE_ID')
                        if (zoneRes.code != 200) {
                            error("Zone アクセス検証に失敗しました: HTTP ${zoneRes.code}\n${zoneRes.raw}")
                        }
                        def zoneName = zoneRes.body.result?.name
                        def acctId   = zoneRes.body.result?.account?.id
                        echo "Zone : ${zoneName} (account=${acctId})"

                        if (!env.CF_ACCOUNT_ID?.trim()) {
                            if (!acctId) {
                                error('Zone から account.id を取得できませんでした。CF_ACCOUNT_ID を明示してください。')
                            }
                            env.CF_ACCOUNT_ID = acctId
                        }
                        echo "使用 Account ID: ${env.CF_ACCOUNT_ID}"

                        // カスタムアドレスのドメインが Zone と一致するかチェック（誤ゾーン保護）
                        def customDomain = env.CUSTOM_ADDR.split('@', 2)[1]
                        if (zoneName && !(customDomain == zoneName || customDomain.endsWith('.' + zoneName))) {
                            error("カスタムアドレスのドメイン '${customDomain}' が Zone '${zoneName}' に属していません。")
                        }
                    }
                }
            }
        }

        stage('Ensure Destination Address') {
            steps {
                script {
                    withCloudflareCredentials {
                        echo '=== 既存の転送先アドレスを取得 ==='
                        def listRes = cfGetByPath('/accounts/$CF_ACCOUNT_ID/email/routing/addresses?per_page=50')
                        if (listRes.code != 200) {
                            error("転送先一覧の取得に失敗しました: HTTP ${listRes.code}\n${listRes.raw}")
                        }
                        def addresses = (listRes.body.result ?: []).collect { it.email?.toString() }
                        echo "登録済み転送先: ${addresses.size()} 件"

                        if (addresses.contains(env.DEST_ADDR)) {
                            echo "転送先は登録済みです: ${env.DEST_ADDR}（更新不要）"
                            env.DEST_NEWLY_CREATED = 'false'
                            return
                        }

                        echo "=== 転送先を新規登録: ${env.DEST_ADDR} ==="
                        def createRes = cfPostByPath(
                            '/accounts/$CF_ACCOUNT_ID/email/routing/addresses',
                            [email: env.DEST_ADDR]
                        )
                        if (createRes.code != 200) {
                            def errMsg = createRes.body?.errors?.getAt(0)?.message ?: "HTTP ${createRes.code}"
                            error("転送先の登録に失敗しました: ${errMsg}\n${createRes.raw}")
                        }
                        env.DEST_NEWLY_CREATED = 'true'
                        echo "転送先の登録要求を送信しました。Cloudflare から ${env.DEST_ADDR} に確認メールが届きます。"
                        echo '※ ルールは有効化されますが、転送先が verified になるまで実際の配信は始まりません。'
                    }
                }
            }
        }

        stage('Create / Update Routing Rule') {
            steps {
                script {
                    withCloudflareCredentials {
                        echo '=== 既存ルール検索 ==='
                        def listRes = cfGetByPath('/zones/$CF_ZONE_ID/email/routing/rules?per_page=50')
                        if (listRes.code != 200) {
                            error("ルール一覧の取得に失敗しました: HTTP ${listRes.code}\n${listRes.raw}")
                        }
                        def rules = listRes.body.result ?: []
                        def existing = rules.find { rule ->
                            (rule.matchers ?: []).any { m ->
                                m.type == 'literal' && m.field == 'to' && m.value == env.CUSTOM_ADDR
                            }
                        }

                        def desiredBody = [
                            name    : env.RULE_NAME_EFFECTIVE,
                            enabled : params.RULE_ENABLED,
                            matchers: [[type: 'literal', field: 'to', value: env.CUSTOM_ADDR]],
                            actions : [[type: 'forward', value: [env.DEST_ADDR]]],
                        ]

                        if (existing) {
                            def currentDest = existing.actions?.getAt(0)?.value ?: []
                            def needsUpdate = (
                                existing.name != desiredBody.name ||
                                existing.enabled != desiredBody.enabled ||
                                currentDest != [env.DEST_ADDR]
                            )
                            if (!needsUpdate) {
                                echo "ルールは最新です（更新不要）: ${existing.tag}"
                                return
                            }
                            echo "=== 既存ルールを更新: ${existing.tag} ==="
                            def updRes = cfPutByPath(
                                "/zones/\$CF_ZONE_ID/email/routing/rules/${existing.tag}",
                                desiredBody
                            )
                            if (updRes.code != 200) {
                                def errMsg = updRes.body?.errors?.getAt(0)?.message ?: "HTTP ${updRes.code}"
                                error("ルール更新に失敗しました: ${errMsg}\n${updRes.raw}")
                            }
                            echo "ルールを更新しました: ${existing.tag}"
                        } else {
                            echo '=== ルールを新規作成 ==='
                            def createRes = cfPostByPath(
                                '/zones/$CF_ZONE_ID/email/routing/rules',
                                desiredBody
                            )
                            if (createRes.code != 200) {
                                def errMsg = createRes.body?.errors?.getAt(0)?.message ?: "HTTP ${createRes.code}"
                                error("ルール作成に失敗しました: ${errMsg}\n${createRes.raw}")
                            }
                            echo "ルールを作成しました: tag=${createRes.body.result?.tag}"
                        }
                    }
                }
            }
        }
    }

    post {
        success {
            echo "Email Routing 設定完了: ${env.CUSTOM_ADDR} → ${env.DEST_ADDR}"
            script {
                if (env.DEST_NEWLY_CREATED == 'true') {
                    echo "注意: 転送先 ${env.DEST_ADDR} は新規登録のため、受信ボックスで確認メールの認証を完了してください。"
                }
            }
        }
        failure { echo 'Email Routing 設定が失敗しました。コンソールログを確認してください。' }
    }
}

// ============================================================
// HTTP ヘルパー関数（withCredentials スコープ内から呼び出す）
//   CF_API_TOKEN はシェル変数として参照するため Groovy 補間しない
// ============================================================

/** GET リクエスト（絶対 URL） */
def cfGet(String url) {
    def raw = sh(
        script: """curl -s -w '\\n%{http_code}' \
            -H "Authorization: Bearer \$CF_API_TOKEN" \
            '${url}'""",
        returnStdout: true
    ).trim()
    return parseResponse(raw)
}

/** API パス（$CF_ZONE_ID / $CF_ACCOUNT_ID を含む可）から GET */
def cfGetByPath(String apiPath) {
    return cfRequestByPath('GET', apiPath, null)
}

/** API パスへ POST（Map body を JSON 化して送信） */
def cfPostByPath(String apiPath, Map bodyMap) {
    return cfRequestByPath('POST', apiPath, bodyMap)
}

/** API パスへ PUT */
def cfPutByPath(String apiPath, Map bodyMap) {
    return cfRequestByPath('PUT', apiPath, bodyMap)
}

/** 任意メソッドのリクエスト本体 */
def cfRequestByPath(String method, String apiPath, Map bodyMap) {
    def envMap = [
        "CF_API_PATH_TEMPLATE=${apiPath}",
        "CF_API_METHOD=${method}",
    ]
    def hasBody = bodyMap != null
    def tmpFile = null
    if (hasBody) {
        tmpFile = "${env.WORKSPACE}/.cf_body.json"
        writeFile file: tmpFile, text: groovy.json.JsonOutput.toJson(bodyMap)
        envMap << "CF_TMP_FILE=${tmpFile}"
    }

    def raw
    withEnv(envMap) {
        if (hasBody) {
            raw = sh(
                script: '''
CF_API_PATH="$(printf '%s' "$CF_API_PATH_TEMPLATE" | awk -v z="$CF_ZONE_ID" -v a="$CF_ACCOUNT_ID" '{gsub(/\\$CF_ZONE_ID/, z); gsub(/\\$CF_ACCOUNT_ID/, a); print}')"
curl -s -w "\\n%{http_code}" -X "$CF_API_METHOD" -H "Authorization: Bearer $CF_API_TOKEN" -H "Content-Type: application/json" --data "@$CF_TMP_FILE" "$CF_API_BASE$CF_API_PATH"
''',
                returnStdout: true
            ).trim()
        } else {
            raw = sh(
                script: '''
CF_API_PATH="$(printf '%s' "$CF_API_PATH_TEMPLATE" | awk -v z="$CF_ZONE_ID" -v a="$CF_ACCOUNT_ID" '{gsub(/\\$CF_ZONE_ID/, z); gsub(/\\$CF_ACCOUNT_ID/, a); print}')"
curl -s -w "\\n%{http_code}" -X "$CF_API_METHOD" -H "Authorization: Bearer $CF_API_TOKEN" "$CF_API_BASE$CF_API_PATH"
''',
                returnStdout: true
            ).trim()
        }
    }

    if (tmpFile) {
        sh "rm -f '${tmpFile}'"
    }
    return parseResponse(raw)
}

/** curl レスポンス（本文 + ステータスコード）をパース */
def parseResponse(String raw) {
    def lines   = raw.split('\n') as List
    def code    = lines.last().toInteger()
    def bodyTxt = lines[0..-2].join('\n')
    def body    = readJSON(text: bodyTxt ?: '{}')
    return [code: code, body: body, raw: bodyTxt]
}

/** Cloudflare credential ID の差分を吸収して実行する */
def withCloudflareCredentials(Closure body) {
    def envToken   = env.CF_API_TOKEN?.trim()
    def envZoneId  = env.CF_ZONE_ID?.trim()
    def envAcctId  = env.CF_ACCOUNT_ID?.trim()
    if (envToken && envZoneId) {
        echo 'Cloudflare 認証情報を環境変数から直接取得します。'
        def envs = ["CF_API_TOKEN=${envToken}", "CF_ZONE_ID=${envZoneId}"]
        // CF_ACCOUNT_ID は未解決の可能性があるため、値が無い場合は withEnv で空マスクせず
        // 後段（Verify ステージ）の env.CF_ACCOUNT_ID 解決結果をそのまま参照させる
        if (envAcctId) { envs << "CF_ACCOUNT_ID=${envAcctId}" }
        withEnv(envs) {
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
                // 任意の Account ID Credential（無くてもOK。Zone から自動解決する）
                def acctCandidates = ['CF_ACCOUNT_ID', 'cf-account-id']
                def acctApplied = false
                for (def acctId in acctCandidates) {
                    try {
                        withCredentials([string(credentialsId: acctId, variable: 'CF_ACCOUNT_ID')]) {
                            echo "Cloudflare Account ID を Credential から取得: '${acctId}'"
                            body.call()
                        }
                        acctApplied = true
                        break
                    } catch (Exception inner) {
                        if (!(inner.getMessage() ?: '').contains('Could not find credentials entry with ID')) {
                            throw inner
                        }
                    }
                }
                if (!acctApplied) {
                    // Account ID Credential が無いケース：env.CF_ACCOUNT_ID（Verify ステージで解決）を
                    // そのまま使わせるため、空文字での withEnv マスクは行わない
                    body.call()
                }
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
