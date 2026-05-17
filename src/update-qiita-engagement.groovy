import groovy.transform.Field

def libBranch = env.CHANGE_BRANCH ?: env.BRANCH_NAME ?: 'main'
def libId = "jqit-lib@${libBranch}"

try {
    library libId
} catch (err) {
    echo "${libId} の読み込みに失敗しました。jqit-lib@main へフォールバックします。"
    library 'jqit-lib@main'
}

/*
 * ============================================================
 * 公開 Qiita Organization 新着記事 自動エンゲージメント Pipeline
 * ============================================================
 *
 * ■目的
 *   指定した公開 Qiita Organization の新着記事に対して、
 *   自動で「いいね」と「ストック」を付与する。
 *
 * ■対象
 *   - 公開 Qiita (https://qiita.com)
 *   - Organization に属する公開記事
 *
 * ■非対象
 *   - Qiita Team
 *   - 非公開記事
 *
 * ■必要な Jenkins Credential
 *   Kind: Secret text
 *   Credential ID 例:
 *     - jqit-qiita-access-token
 *     - personal-qiita-access-token
 *     - lberc-qiita-access-token
 *     - honoka-qiita-access-token
 *     - umi-qiita-access-token
 *   値: write_qiita を含む Qiita API Token
 *
 * ■主な特徴
 *   - Organization の記事候補を Qiita API（フォールバック: 公開フィード）から抽出
 *   - 処理済み item_id を state ファイルへ保存
 *   - DRY_RUN で書き込みなし検証が可能
 *   - 429 / 5xx / 通信失敗相当に対して簡易リトライ
 *   - 一部失敗時は UNSTABLE 扱い
 */

@Field def qiitaConfig = [:]

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

    /*
     * 30分ごとに起動。H を使って負荷分散。
     * 4 時台は sync-qiita-profile-share と被るので除外する。
     */
    triggers {
        cron('TZ=Asia/Tokyo\nH/30 0-3,5-23 * * *')
    }

    options {
        skipDefaultCheckout(true)
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    parameters {
        string(
            name: 'TARGET_ORGANIZATIONS',
            defaultValue: 'jqiit-co',
            description: '監視対象の公開 Qiita organization_url_name（カンマ区切り）'
        )

        booleanParam(
            name: 'DO_LIKE',
            defaultValue: true,
            description: 'true の場合、記事へ「いいね」を付与する'
        )

        booleanParam(
            name: 'DO_STOCK',
            defaultValue: true,
            description: 'true の場合、記事をストックする'
        )

        booleanParam(
            name: 'DRY_RUN',
            defaultValue: false,
            description: 'true の場合、API更新をせず対象抽出のみ行う'
        )

        string(
            name: 'STATE_FILE_PATH',
            defaultValue: '',
            description: '処理済み記事IDの保存先。空欄時は $HOME/.qiita-auto-engagement/processed_item_ids.txt'
        )

        string(
            name: 'MAX_STATE_IDS',
            defaultValue: '30000',
            description: 'state に保持する最大記事ID件数'
        )

        string(
            name: 'QIITA_TOKEN_CREDENTIAL_ID',
            defaultValue: 'jqit-qiita-access-token',
            description: 'Qiita API Token の Jenkins Credential ID（単一運用時。複数運用時は QIITA_TOKEN_CREDENTIAL_IDS を優先）'
        )

        string(
            name: 'QIITA_TOKEN_CREDENTIAL_IDS',
            defaultValue: 'jqit-qiita-access-token,personal-qiita-access-token,lberc-qiita-access-token,honoka-qiita-access-token,umi-qiita-access-token,kotori-qiita-access-token,nico-qiita-access-token,eri-qiita-access-token,nozomi-qiita-access-token,maki-qiita-access-token,rin-qiita-access-token,hanayo-qiita-access-token,anju-qiita-access-token,erena-qiita-access-token,tsubasa-qiita-access-token',
            description: 'Qiita API Token の Jenkins Credential ID（カンマ区切り）。例: jqit-...,personal-...,lberc-...'
        )

        string(
            name: 'HTTP_TIMEOUT_SEC',
            defaultValue: '30',
            description: 'Qiita API / Feed アクセス時の curl タイムアウト秒'
        )

        string(
            name: 'HTTP_RETRY_COUNT',
            defaultValue: '3',
            description: '429 / 5xx / ネットワークエラー時のリトライ回数'
        )

        string(
            name: 'API_THROTTLE_MS',
            defaultValue: '1500',
            description: '各書き込み API（like/stock）の間に挿入する待機ミリ秒。Qiita の spam 検知回避'
        )
    }

    environment {
        /*
         * 公開 Qiita API のベースURL
         */
        QIITA_API_BASE = 'https://qiita.com/api/v2'

        /*
         * state ファイル既定値
         */
        DEFAULT_STATE_DIR  = "${HOME}/.qiita-auto-engagement"
        DEFAULT_STATE_FILE = "${HOME}/.qiita-auto-engagement/processed_item_ids.txt"
    }

    stages {
        stage('Checkout SCM') {
            when {
                beforeAgent true
                expression {
                    // 前回ビルドが実行中なら今回は丸ごとスキップする（多重実行防止）
                    if (concurrentRunInProgress()) {
                        env.CONCURRENCY_SKIP = 'true'
                        return false
                    }
                    env.CONCURRENCY_SKIP = 'false'
                    return true
                }
            }
            steps {
                echo 'SCM チェックアウトをスキップします（パイプラインソースはSCMから読み込み済み）。'
            }
        }

        stage('Validate Settings') {
            when {
                beforeAgent true
                expression { return env.CONCURRENCY_SKIP != 'true' }
            }
            steps {
                script {
                    /*
                     * カンマ区切りの organization 名を配列へ整形。
                     */
                    def organizations = qiitaEngagementUtils.parseCsv(params.TARGET_ORGANIZATIONS)

                    if (organizations.isEmpty()) {
                        error('TARGET_ORGANIZATIONS が空です。公開 organization_url_name を1つ以上指定してください。')
                    }

                    if (!params.DO_LIKE && !params.DO_STOCK) {
                        error('DO_LIKE と DO_STOCK が両方 false です。いずれか一方を有効にしてください。')
                    }

                    def credentialIds = qiitaEngagementUtils.parseCsv(params.QIITA_TOKEN_CREDENTIAL_IDS)
                    if (credentialIds.isEmpty()) {
                        def singleCredentialId = (params.QIITA_TOKEN_CREDENTIAL_ID ?: '').trim()
                        if (singleCredentialId) {
                            credentialIds = [singleCredentialId]
                        }
                    }

                    if (credentialIds.isEmpty()) {
                        error('QIITA_TOKEN_CREDENTIAL_IDS / QIITA_TOKEN_CREDENTIAL_ID が空です。')
                    }

                    int maxStateIds   = qiitaEngagementUtils.toBoundedInt(params.MAX_STATE_IDS, 30000, 100, 50000)
                    int httpTimeout   = qiitaEngagementUtils.toBoundedInt(params.HTTP_TIMEOUT_SEC, 30, 5, 300)
                    int httpRetry     = qiitaEngagementUtils.toBoundedInt(params.HTTP_RETRY_COUNT, 3, 1, 10)
                    int apiThrottleMs = qiitaEngagementUtils.toBoundedInt(params.API_THROTTLE_MS, 1500, 0, 30000)

                    def stateFilePath = (params.STATE_FILE_PATH ?: '').trim()
                    if (!stateFilePath) {
                        stateFilePath = env.DEFAULT_STATE_FILE
                    }

                    def stateDirPath = sh(
                        script: "dirname '${qiitaEngagementUtils.shellQuote(stateFilePath)}'",
                        returnStdout: true
                    ).trim()

                    qiitaConfig = [
                        organizations : organizations,
                        doLike        : params.DO_LIKE,
                        doStock       : params.DO_STOCK,
                        dryRun        : params.DRY_RUN,
                        credentialIds : credentialIds,
                        stateFile     : stateFilePath,
                        stateDir      : stateDirPath,
                        maxStateIds   : maxStateIds,
                        httpTimeoutSec: httpTimeout,
                        httpRetryCount: httpRetry,
                        apiThrottleMs : apiThrottleMs
                    ]

                    echo "対象 Organization    : ${qiitaConfig.organizations.join(', ')}"
                    echo "実行アクション   : いいね=${qiitaConfig.doLike}, ストック=${qiitaConfig.doStock}, DRY_RUN=${qiitaConfig.dryRun}"
                    echo "State ファイル    : ${qiitaConfig.stateFile}"
                    echo "State 最大件数  : ${qiitaConfig.maxStateIds}"
                    echo "HTTP タイムアウト: ${qiitaConfig.httpTimeoutSec}s"
                    echo "HTTP リトライ回数: ${qiitaConfig.httpRetryCount}"
                    echo "API スロットル  : ${qiitaConfig.apiThrottleMs}ms"
                    echo "Credential IDs : ${qiitaConfig.credentialIds.join(', ')}"
                }
            }
        }

        stage('Verify Qiita Token') {
            when {
                beforeAgent true
                expression { return env.CONCURRENCY_SKIP != 'true' }
            }
            steps {
                script {
                    def activeCredentialIds = []

                    for (String credentialId : qiitaConfig.credentialIds) {
                        try {
                            withCredentials([string(credentialsId: credentialId, variable: 'QIITA_TOKEN')]) {
                                /*
                                 * /authenticated_user で token の有効性と権限を確認。
                                 */
                                def whoami = qiitaEngagementUtils.qiitaGet(qiitaConfig, env.QIITA_API_BASE, '/authenticated_user')
                                if (whoami.code != 200) {
                                    echo "[WARN] Qiita トークン検証に失敗したためスキップします: credentialId=${credentialId}, HTTP=${whoami.code}, body=${qiitaEngagementUtils.trimForLog(whoami.rawBody)}"
                                } else {
                                    def userId = (whoami.body instanceof Map) ? (whoami.body.id ?: 'unknown') : 'unknown'
                                    echo "認証ユーザー: credentialId=${credentialId}, user=${userId}"
                                    activeCredentialIds << credentialId
                                }
                            }
                        } catch (Exception ex) {
                            echo "[WARN] Credential の読み込みまたは検証に失敗したためスキップします: credentialId=${credentialId}, reason=${ex.message}"
                        }
                    }

                    if (activeCredentialIds.isEmpty()) {
                        error('有効な Qiita トークンが1件もありません。Credential 設定を確認してください。')
                    }

                    qiitaConfig.activeCredentialIds = activeCredentialIds
                    echo "有効 Credential IDs: ${qiitaConfig.activeCredentialIds.join(', ')}"
                }
            }
        }

        stage('Monitor & Engage') {
            when {
                beforeAgent true
                expression { return env.CONCURRENCY_SKIP != 'true' }
            }
            steps {
                script {
                    /*
                     * state ディレクトリ作成
                     */
                    sh "mkdir -p '${qiitaEngagementUtils.shellQuote(qiitaConfig.stateDir)}'"

                    /*
                     * 既存の処理済み item_id 一覧をロード
                     */
                    def existingIds = qiitaEngagementUtils.loadStateIds(qiitaConfig.stateFile)
                    def existingSet = existingIds as Set
                    echo "処理済み ID 読込: ${existingIds.size()} 件"

                    def targets = []

                    def readerCredentialId = qiitaConfig.activeCredentialIds[0]

                    withCredentials([string(credentialsId: readerCredentialId, variable: 'QIITA_TOKEN')]) {
                        /*
                         * 各 Organization の記事候補を検出する。
                         * Qiita API (query=org:{org}) を主取得手段とし、
                         * 失敗時のみ activities.atom にフォールバックする。
                         */
                        for (String orgName : qiitaConfig.organizations) {
                            def orgItems = qiitaEngagementUtils.discoverOrganizationItems(qiitaConfig, env.QIITA_API_BASE, orgName)

                            if (orgItems.isEmpty()) {
                                echo "[INFO] Organization の記事が見つかりませんでした: ${orgName}"
                                continue
                            }

                            echo "Organization 記事を ${orgItems.size()} 件検出: ${orgName}"
                            targets.addAll(orgItems)
                        }

                        /*
                         * 非公開記事は除外。
                         * 同一 item_id は重複排除。
                         * created_at 昇順で古いものから処理。
                         */
                        targets = qiitaEngagementUtils.normalizeTargets(targets)

                        if (targets.isEmpty()) {
                            echo '新しい公開記事は見つかりませんでした。'
                            return
                        }

                        int likedCount    = 0
                        int stockedCount  = 0
                        int failedCount   = 0
                        int skippedCount  = 0
                        def processedKeys = []

                        /*
                         * トークン単位で engage を並列実行する。
                         * 各 Credential は別 Qiita アカウントのため、レート制限・spam 判定は
                         * 互いに独立しており、並列化しても安全。各ブランチ内は従来どおり
                         * API_THROTTLE_MS で直列スロットルする。
                         */
                        def engageBranches = [:]
                        def branchResults = [:]
                        qiitaConfig.activeCredentialIds.each { branchResults[it] = null }

                        for (String credentialId : qiitaConfig.activeCredentialIds) {
                            def cid = credentialId
                            engageBranches["engage-${cid}"] = {
                                branchResults[cid] = engageWithToken(cid, targets, existingSet)
                            }
                        }

                        parallel engageBranches

                        // 各ブランチの集計結果をマージする
                        for (def r : branchResults.values()) {
                            if (r == null) {
                                continue
                            }
                            likedCount   += r.liked
                            stockedCount += r.stocked
                            failedCount  += r.failed
                            skippedCount += r.skipped
                            processedKeys.addAll(r.processedKeys)
                        }

                        /*
                         * 新規成功分 + 既存 state をマージ。
                         * 最大件数に制限。
                         */
                        def merged = []
                        merged.addAll(processedKeys)
                        merged.addAll(existingIds)

                        merged = qiitaEngagementUtils.normalizeStateIds(merged, qiitaConfig.maxStateIds)

                        qiitaEngagementUtils.saveStateIds(qiitaConfig.stateFile, merged)

                        echo "実行結果:"
                        echo "  対象記事数 = ${targets.size()}"
                        echo "  いいね数   = ${likedCount}"
                        echo "  ストック数 = ${stockedCount}"
                        echo "  スキップ数 = ${skippedCount}"
                        echo "  失敗数   = ${failedCount}"
                        echo "  State件数  = ${merged.size()}"

                        /*
                         * 一部失敗がある場合は UNSTABLE にする。
                         */
                        if (failedCount > 0) {
                            currentBuild.result = 'UNSTABLE'
                            echo '一部のアクションが失敗しました。ビルドを UNSTABLE に設定します。'
                        }
                    }
                }
            }
        }
    }

    post {
        success {
            echo 'Qiita 公開エンゲージメントパイプラインが正常に完了しました。'
        }
        unstable {
            echo 'Qiita 公開エンゲージメントパイプラインが一部失敗で完了しました（UNSTABLE）。'
        }
        failure {
            echo 'Qiita 公開エンゲージメントパイプラインが失敗しました。コンソールログを確認してください。'
        }
        always {
            script {
                /*
                 * 念のため一時ファイル掃除
                 */
                sh '''
                    rm -f /tmp/qiita_*.headers /tmp/qiita_*.body /tmp/qiita_org_feed_*.xml 2>/dev/null || true
                '''
            }
        }
    }
}

/* ============================================================
 * Helper Functions
 * ============================================================
 */

/*
 * カンマ区切り文字列を配列へ変換する。
 * 余計な空白を除去し、空要素を捨て、重複を排除する。
 */

/*
 * state ファイルから処理済み item_id を読み込む。
 */
def loadStateIds(String stateFilePath) {
    def output = sh(
        script: "cat '${qiitaEngagementUtils.shellQuote(stateFilePath)}' 2>/dev/null || true",
        returnStdout: true
    ).trim()

    if (!output) {
        return []
    }

    return output.readLines()
        .collect { it.trim() }
        .findAll { it }
        .unique()
}

/*
 * state ファイルへ処理済み item_id 一覧を書き込む。
 */
def saveStateIds(String stateFilePath, List ids) {
    def content = ids ? (ids.join('\n') + '\n') : ''
    writeFile(file: stateFilePath, text: content)
}

/*
 * Organization の公開フィードから記事ID候補を抽出する。
 *
 * まず activities.atom を試し、
 * 失敗時は /feed をフォールバックとして試す。
 *
 * フィード本文に含まれる記事URLから item_id を正規表現で抽出する。
 */
def discoverOrganizationItemIds(String orgName) {
    List<String> candidateUrls = [
        "https://qiita.com/organizations/${orgName}/activities.atom",
        "https://qiita.com/organizations/${orgName}/feed"
    ]

    String feedXml = ''
    String usedUrl = ''

    for (String url : candidateUrls) {
        def res = httpGetText(url, false)
        if (res.code == 200 && (res.body ?: '').toString().trim()) {
            feedXml = res.body.toString()
            usedUrl = url
            break
        }
        echo "[INFO] フィード取得失敗またはレスポンスが空: org=${orgName}, url=${url}, HTTP=${res.code}"
    }

    if (!feedXml) {
        echo "[WARN] Organization の公開フィードを取得できませんでした: ${orgName}"
        return []
    }

    echo "使用する Organization フィード: ${usedUrl}"

    def matcher = (feedXml =~ /https:\/\/qiita\.com\/[^\/<"\s]+\/items\/([A-Za-z0-9]+)/)
    def ids = []

    while (matcher.find()) {
        ids << matcher.group(1)
    }

    return ids.findAll { it }.unique()
}

/*
 * Qiita GET ラッパー
 */
def qiitaGet(String apiPath) {
    return qiitaRequest('GET', apiPath, null)
}

/*
 * Qiita PUT ラッパー
 */
def qiitaPut(String apiPath) {
    return qiitaRequest('PUT', apiPath, null)
}

/*
 * Qiita API リクエスト共通処理
 */
def qiitaRequest(String method, String apiPath, String requestBody) {
    String url = "${env.QIITA_API_BASE}${apiPath}"
    return httpRequestWithRetry(
        method,
        url,
        true,
        requestBody,
        requestBody != null ? 'application/json' : null
    )
}

/*
 * 認証不要な GET 用。
 * Organization の公開フィード取得などで使用する。
 */
def httpGetText(String url, boolean withAuth) {
    return httpRequestWithRetry('GET', url, withAuth, null, null)
}

/*
 * リトライ付き HTTP リクエスト。
 *
 * リトライ対象:
 * - code=0
 * - 408
 * - 425
 * - 429
 * - 5xx
 */
def httpRequestWithRetry(String method, String url, boolean withAuth, String requestBody, String contentType) {
    int maxAttempts = qiitaConfig?.httpRetryCount ?: 3
    int timeoutSec  = qiitaConfig?.httpTimeoutSec ?: 30

    def lastRes = [code: 0, body: '', rawBody: '', headers: '']

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        lastRes = httpRequestOnce(method, url, withAuth, requestBody, contentType, timeoutSec)

        boolean retryable = (
            lastRes.code == 0 ||
            lastRes.code == 408 ||
            lastRes.code == 425 ||
            lastRes.code == 429 ||
            (lastRes.code >= 500 && lastRes.code <= 599)
        )

        if (!retryable || attempt == maxAttempts) {
            return lastRes
        }

        int sleepSec = Math.min(30, attempt * 3)
        echo "[WARN] リトライ対象の HTTP 結果 ${method} ${url}: code=${lastRes.code}。リトライ ${attempt}/${maxAttempts}（${sleepSec}秒後）。"
        sleep(time: sleepSec, unit: 'SECONDS')
    }

    return lastRes
}

/*
 * HTTP リクエスト単発実行。
 *
 * - レスポンスヘッダを一時ファイルへ保存
 * - レスポンスボディを一時ファイルへ保存
 * - HTTP code を別で取得
 * - body は JSON なら parse、違えば文字列で返す
 */
def httpRequestOnce(String method, String url, boolean withAuth, String requestBody, String contentType, int timeoutSec) {
    String tokenHeader = withAuth ? '-H "Authorization: Bearer $QIITA_TOKEN"' : ''
    String contentTypeHeader = contentType ? """-H "Content-Type: ${contentType}" """ : ''
    String dataOption = ''

    if (requestBody != null) {
        String escapedBody = requestBody.replace("'", "'\"'\"'")
        dataOption = "--data '${escapedBody}'"
    }

    String uid = UUID.randomUUID().toString().replace('-', '')
    String headerFile = "/tmp/qiita_${uid}.headers"
    String bodyFile   = "/tmp/qiita_${uid}.body"

    String codeText = sh(
        script: """
            set +e
            curl -sS \\
              --connect-timeout ${timeoutSec} \\
              --max-time ${timeoutSec} \\
              -X ${method} \\
              ${tokenHeader} \\
              ${contentTypeHeader} \\
              ${dataOption} \\
              -D '${headerFile}' \\
              -o '${bodyFile}' \\
              -w '%{http_code}' \\
              '${url}'
            rc=\$?
            if [ "\$rc" -ne 0 ]; then
              echo 0
            fi
        """,
        returnStdout: true
    ).trim()

    String headers = sh(
        script: "cat '${headerFile}' 2>/dev/null || true",
        returnStdout: true
    )

    String bodyText = sh(
        script: "cat '${bodyFile}' 2>/dev/null || true",
        returnStdout: true
    )

    sh "rm -f '${headerFile}' '${bodyFile}' 2>/dev/null || true"

    int code
    try {
        code = (codeText ?: '0') as Integer
    } catch (Exception ignored) {
        code = 0
    }

    def parsedBody = parseBody(bodyText)

    return [
        code   : code,
        body   : parsedBody,
        rawBody: bodyText ?: '',
        headers: headers ?: ''
    ]
}

/*
 * レスポンス body を解釈する。
 *
 * JSON オブジェクト/配列なら parse し、
 * それ以外は文字列のまま返す。
 */
def parseBody(String bodyText) {
    def text = (bodyText ?: '').trim()
    if (!text) {
        return [:]
    }

    if ((text.startsWith('{') && text.endsWith('}')) || (text.startsWith('[') && text.endsWith(']'))) {
        try {
            return readJSON(text: text)
        } catch (Exception ignored) {
            return text
        }
    }

    return text
}

/*
 * 同一ジョブの前回ビルドが実行中かどうかを判定する（多重実行防止）。
 * 実行中を検出した場合は currentBuild を NOT_BUILT にし、ログを出して true を返す。
 * 直近 30 ビルドまで遡って確認する（スキップ済みビルドを挟んでも検出できるように）。
 */
boolean concurrentRunInProgress() {
    def b = currentBuild.previousBuild
    int checked = 0
    while (b != null && checked < 30) {
        if (b.result == null) {
            echo "前回ビルド #${b.number} が実行中のため、今回はスキップします。"
            currentBuild.result = 'NOT_BUILT'
            return true
        }
        b = b.previousBuild
        checked++
    }
    return false
}

/*
 * 指定 Credential（Qiita トークン）で全 target 記事に like / stock を実行する。
 * parallel ブランチから安全に呼べるよう、集計は呼び出し側へ Map で返し、
 * 共有変数を直接加算しない（[liked, stocked, failed, skipped, processedKeys]）。
 */
def engageWithToken(String credentialId, List targets, Set existingSet) {
    def r = [liked: 0, stocked: 0, failed: 0, skipped: 0, processedKeys: []]

    withCredentials([string(credentialsId: credentialId, variable: 'QIITA_TOKEN')]) {
        echo "[INFO] いいね/ストック実行中: credentialId=${credentialId}"

        for (def target : targets) {
            def stateKeyBase = "${credentialId}:${target.id}"
            def legacyStateKey = stateKeyBase
            def likeStateKey = "${stateKeyBase}:like"
            def stockStateKey = "${stateKeyBase}:stock"

            boolean likeAlreadyProcessed = existingSet.contains(legacyStateKey) || existingSet.contains(likeStateKey)
            boolean stockAlreadyProcessed = existingSet.contains(legacyStateKey) || existingSet.contains(stockStateKey)

            if ((!qiitaConfig.doLike || likeAlreadyProcessed) && (!qiitaConfig.doStock || stockAlreadyProcessed)) {
                r.skipped++
                continue
            }

            echo "対象記事: [${target.org}] ${target.title} (${target.id})"
            echo "URL     : ${target.url}"
            echo "日付    : 作成=${target.createdAt}, 更新=${target.updatedAt}"
            echo "実行対象 Credential: ${credentialId}"

            boolean likeOk  = !qiitaConfig.doLike || likeAlreadyProcessed
            boolean stockOk = !qiitaConfig.doStock || stockAlreadyProcessed

            if (qiitaConfig.dryRun) {
                /*
                 * DRY_RUN では API 更新せず、成功扱いで進める。
                 */
                echo "[DRY_RUN] ${target.id} への書き込みをスキップします。"
                likeOk = true
                stockOk = true
                if (qiitaConfig.doLike && !likeAlreadyProcessed) {
                    r.processedKeys << likeStateKey
                    echo "[INFO] いいね実施済み state へ保存: ${likeStateKey}"
                }
                if (qiitaConfig.doStock && !stockAlreadyProcessed) {
                    r.processedKeys << stockStateKey
                    echo "[INFO] ストック実施済み state へ保存: ${stockStateKey}"
                }
            } else {
                int throttleMs = qiitaConfig.apiThrottleMs ?: 0

                if (qiitaConfig.doLike && !likeAlreadyProcessed) {
                    def likeRes = qiitaEngagementUtils.qiitaPut(qiitaConfig, env.QIITA_API_BASE, "/items/${target.id}/like")
                    if (qiitaEngagementUtils.isSuccessCode(likeRes.code)) {
                        likeOk = true
                        r.liked++
                        r.processedKeys << likeStateKey
                        echo "[INFO] いいね実施済み state へ保存: ${likeStateKey}"
                    } else if (likeRes.code == 409) {
                        /*
                         * 既に like 済みなど、競合扱いを許容する場合。
                         */
                        likeOk = true
                        r.skipped++
                        r.processedKeys << likeStateKey
                        echo "[INFO] いいね済みまたは競合のため成功扱い・state へ保存: ${likeStateKey}"
                    } else if (qiitaEngagementUtils.isAlreadyLikeResponse(likeRes)) {
                        likeOk = true
                        r.skipped++
                        r.processedKeys << likeStateKey
                        echo "[INFO] いいね済み（403 already_liked）のため成功扱い・state へ保存: ${likeStateKey}"
                    } else {
                        likeOk = false
                        echo "[WARN] いいね失敗: credentialId=${credentialId}, id=${target.id}, HTTP=${likeRes.code}, body=${qiitaEngagementUtils.trimForLog(likeRes.rawBody)}"
                    }

                    if (throttleMs > 0) {
                        sleep time: throttleMs, unit: 'MILLISECONDS'
                    }
                }

                if (qiitaConfig.doStock && !stockAlreadyProcessed) {
                    def stockRes = qiitaEngagementUtils.qiitaPut(qiitaConfig, env.QIITA_API_BASE, "/items/${target.id}/stock")
                    if (qiitaEngagementUtils.isSuccessCode(stockRes.code)) {
                        stockOk = true
                        r.stocked++
                        r.processedKeys << stockStateKey
                        echo "[INFO] ストック実施済み state へ保存: ${stockStateKey}"
                    } else if (stockRes.code == 409) {
                        stockOk = true
                        r.skipped++
                        r.processedKeys << stockStateKey
                        echo "[INFO] ストック済みまたは競合のため成功扱い・state へ保存: ${stockStateKey}"
                    } else if (qiitaEngagementUtils.isAlreadyStockResponse(stockRes)) {
                        stockOk = true
                        r.skipped++
                        r.processedKeys << stockStateKey
                        echo "[INFO] ストック済み（403 already_stocked）のため成功扱い・state へ保存: ${stockStateKey}"
                    } else {
                        stockOk = false
                        echo "[WARN] ストック失敗: credentialId=${credentialId}, id=${target.id}, HTTP=${stockRes.code}, body=${qiitaEngagementUtils.trimForLog(stockRes.rawBody)}"
                    }

                    if (throttleMs > 0) {
                        sleep time: throttleMs, unit: 'MILLISECONDS'
                    }
                }
            }

            /*
             * 各アクションの成功状態は個別に state へ保存する。
             * 一部失敗時は未完了アクションのみ次回再試行する。
             */
            if (!likeOk || !stockOk) {
                r.failed++
            }
        }
    }

    return r
}
