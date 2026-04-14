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
 *   値: write_qiita を含む Qiita API Token
 *
 * ■主な特徴
 *   - Organization フィードから記事候補を抽出
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
  containers:
    - name: main
      image: honoka4869/jenkins-maven-node:latest
      command:
        - cat
      tty: true
'''
        }
    }

    /*
     * 30分ごとに起動。
     * H を使って負荷分散。
     */
    triggers {
        cron('TZ=Asia/Tokyo\nH/30 * * * *')
    }

    options {
        skipDefaultCheckout(true)
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    parameters {
        string(
            name: 'TARGET_ORGANIZATIONS',
            defaultValue: 'jqiit-co,lberc',
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
            defaultValue: '5000',
            description: 'state に保持する最大記事ID件数'
        )

        string(
            name: 'QIITA_TOKEN_CREDENTIAL_ID',
            defaultValue: 'jqit-qiita-access-token',
            description: 'Qiita API Token の Jenkins Credential ID（単一運用時。複数運用時は QIITA_TOKEN_CREDENTIAL_IDS を優先）'
        )

        string(
            name: 'QIITA_TOKEN_CREDENTIAL_IDS',
            defaultValue: 'jqit-qiita-access-token,personal-qiita-access-token,lberc-qiita-access-token',
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
            steps {
                echo 'SCM チェックアウトをスキップします（パイプラインソースはSCMから読み込み済み）。'
            }
        }

        stage('Validate Settings') {
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

                    int maxStateIds   = qiitaEngagementUtils.toBoundedInt(params.MAX_STATE_IDS, 5000, 100, 50000)
                    int httpTimeout   = qiitaEngagementUtils.toBoundedInt(params.HTTP_TIMEOUT_SEC, 30, 5, 300)
                    int httpRetry     = qiitaEngagementUtils.toBoundedInt(params.HTTP_RETRY_COUNT, 3, 1, 10)

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
                        httpRetryCount: httpRetry
                    ]

                    echo "対象 Organization    : ${qiitaConfig.organizations.join(', ')}"
                    echo "実行アクション   : いいね=${qiitaConfig.doLike}, ストック=${qiitaConfig.doStock}, DRY_RUN=${qiitaConfig.dryRun}"
                    echo "State ファイル    : ${qiitaConfig.stateFile}"
                    echo "State 最大件数  : ${qiitaConfig.maxStateIds}"
                    echo "HTTP タイムアウト: ${qiitaConfig.httpTimeoutSec}s"
                    echo "HTTP リトライ回数: ${qiitaConfig.httpRetryCount}"
                    echo "Credential IDs : ${qiitaConfig.credentialIds.join(', ')}"
                }
            }
        }

        stage('Verify Qiita Token') {
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
                         * 各 Organization について、フィードから記事候補を検出。
                         */
                        for (String orgName : qiitaConfig.organizations) {
                            def itemIds = qiitaEngagementUtils.discoverOrganizationItemIds(qiitaConfig, orgName)

                            if (itemIds.isEmpty()) {
                                echo "[INFO] Organization の公開フィードから記事 ID が見つかりませんでした: ${orgName}"
                                continue
                            }

                            echo "Organization フィードから ${itemIds.size()} 件の記事 ID を検出: ${orgName}"

                            /*
                             * 各 item_id を詳細APIで確認し、
                             * 本当に対象 Organization の公開記事かチェックする。
                             */
                            for (String itemId : itemIds) {
                                def itemRes = qiitaEngagementUtils.qiitaGet(qiitaConfig, env.QIITA_API_BASE, "/items/${itemId}")
                                if (itemRes.code != 200) {
                                    echo "[WARN] 記事詳細の取得失敗: id=${itemId}, HTTP=${itemRes.code}"
                                    continue
                                }

                                if (!(itemRes.body instanceof Map)) {
                                    echo "[WARN] 記事詳細のレスポンスが予期しない形式です: id=${itemId}"
                                    continue
                                }

                                def item = itemRes.body
                                def itemOrgName = (item.organization_url_name ?: '').toString().trim()

                                /*
                                 * フィード抽出だけだと誤検出余地があるため、
                                 * organization_url_name で最終確認する。
                                 */
                                if (!itemOrgName || itemOrgName != orgName) {
                                    echo "[INFO] 対象 Organization と一致しないため除外: id=${itemId}, 期待=${orgName}, 実際=${itemOrgName ?: '（なし）'}"
                                    continue
                                }

                                targets << [
                                    id         : itemId,
                                    title      : (item.title ?: '').toString(),
                                    url        : (item.url ?: '').toString(),
                                    org        : orgName,
                                    createdAt  : (item.created_at ?: '').toString(),
                                    updatedAt  : (item.updated_at ?: '').toString(),
                                    privateFlg : item.private == true
                                ]
                            }
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

                        for (String credentialId : qiitaConfig.activeCredentialIds) {
                            withCredentials([string(credentialsId: credentialId, variable: 'QIITA_TOKEN')]) {
                                echo "[INFO] いいね/ストック実行中: credentialId=${credentialId}"

                                /*
                                 * 各記事に対して like / stock を実行。
                                 */
                                for (def target : targets) {
                                    def stateKeyBase = "${credentialId}:${target.id}"
                                    def legacyStateKey = stateKeyBase
                                    def likeStateKey = "${stateKeyBase}:like"
                                    def stockStateKey = "${stateKeyBase}:stock"

                                    boolean likeAlreadyProcessed = existingSet.contains(legacyStateKey) || existingSet.contains(likeStateKey)
                                    boolean stockAlreadyProcessed = existingSet.contains(legacyStateKey) || existingSet.contains(stockStateKey)

                                    if ((!qiitaConfig.doLike || likeAlreadyProcessed) && (!qiitaConfig.doStock || stockAlreadyProcessed)) {
                                        skippedCount++
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
                                            processedKeys << likeStateKey
                                            echo "[INFO] いいね実施済み state へ保存: ${likeStateKey}"
                                        }
                                        if (qiitaConfig.doStock && !stockAlreadyProcessed) {
                                            processedKeys << stockStateKey
                                            echo "[INFO] ストック実施済み state へ保存: ${stockStateKey}"
                                        }
                                    } else {
                                        if (qiitaConfig.doLike && !likeAlreadyProcessed) {
                                            def likeRes = qiitaEngagementUtils.qiitaPut(qiitaConfig, env.QIITA_API_BASE, "/items/${target.id}/like")
                                            if (qiitaEngagementUtils.isSuccessCode(likeRes.code)) {
                                                likeOk = true
                                                likedCount++
                                                processedKeys << likeStateKey
                                                echo "[INFO] いいね実施済み state へ保存: ${likeStateKey}"
                                            } else if (likeRes.code == 409) {
                                                /*
                                                 * 既に like 済みなど、競合扱いを許容する場合。
                                                 */
                                                likeOk = true
                                                skippedCount++
                                                processedKeys << likeStateKey
                                                echo "[INFO] いいね済みまたは競合のため成功扱い・state へ保存: ${likeStateKey}"
                                            } else if (qiitaEngagementUtils.isAlreadyLikeResponse(likeRes)) {
                                                likeOk = true
                                                skippedCount++
                                                processedKeys << likeStateKey
                                                echo "[INFO] いいね済み（403 already_liked）のため成功扱い・state へ保存: ${likeStateKey}"
                                            } else {
                                                likeOk = false
                                                echo "[WARN] いいね失敗: credentialId=${credentialId}, id=${target.id}, HTTP=${likeRes.code}, body=${qiitaEngagementUtils.trimForLog(likeRes.rawBody)}"
                                            }
                                        }

                                        if (qiitaConfig.doStock && !stockAlreadyProcessed) {
                                            def stockRes = qiitaEngagementUtils.qiitaPut(qiitaConfig, env.QIITA_API_BASE, "/items/${target.id}/stock")
                                            if (qiitaEngagementUtils.isSuccessCode(stockRes.code)) {
                                                stockOk = true
                                                stockedCount++
                                                processedKeys << stockStateKey
                                                echo "[INFO] ストック実施済み state へ保存: ${stockStateKey}"
                                            } else if (stockRes.code == 409) {
                                                stockOk = true
                                                skippedCount++
                                                processedKeys << stockStateKey
                                                echo "[INFO] ストック済みまたは競合のため成功扱い・state へ保存: ${stockStateKey}"
                                            } else if (qiitaEngagementUtils.isAlreadyStockResponse(stockRes)) {
                                                stockOk = true
                                                skippedCount++
                                                processedKeys << stockStateKey
                                                echo "[INFO] ストック済み（403 already_stocked）のため成功扱い・state へ保存: ${stockStateKey}"
                                            } else {
                                                stockOk = false
                                                echo "[WARN] ストック失敗: credentialId=${credentialId}, id=${target.id}, HTTP=${stockRes.code}, body=${qiitaEngagementUtils.trimForLog(stockRes.rawBody)}"
                                            }
                                        }
                                    }

                                    /*
                                     * 各アクションの成功状態は個別に state へ保存する。
                                     * 一部失敗時は未完了アクションのみ次回再試行する。
                                     */
                                    if (!likeOk || !stockOk) {
                                        failedCount++
                                    }
                                }
                            }
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
