import groovy.transform.Field

/*
 * ============================================================
 * Qiita プロファイル共有 Pipeline
 * ============================================================
 *
 * 目的:
 * - 複数 Qiita アカウントの「フォロー中ユーザー」「フォロー中タグ」「質問」を収集し、
 *   外部 API にまとめて共有する。
 *
 * 前提:
 * - Jenkins Secret text credential に Qiita API トークンを登録済み
 * - 共有先 API URL が利用可能
 */

@Field def shareConfig = [:]

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
     * 定期実行: 毎日1回（3時台、分はHで分散）
     */
    triggers {
        cron('TZ=Asia/Tokyo\nH 3 * * *')
    }

    options {
        skipDefaultCheckout(true)
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    parameters {
        string(
            name: 'QIITA_TOKEN_CREDENTIAL_IDS',
            defaultValue: 'jqit-qiita-access-token,personal-qiita-access-token,lberc-qiita-access-token',
            description: 'Qiita API Token の Jenkins Credential ID（カンマ区切り）'
        )

        string(
            name: 'SHARE_API_URL',
            defaultValue: '',
            description: '共有先 API URL（例: https://example.com/api/qiita/share）'
        )

        string(
            name: 'SHARE_API_TOKEN_CREDENTIAL_ID',
            defaultValue: '',
            description: '共有先 API 用 Bearer Token の Jenkins Credential ID（任意）'
        )

        string(
            name: 'HTTP_TIMEOUT_SEC',
            defaultValue: '30',
            description: 'HTTP タイムアウト秒'
        )

        string(
            name: 'HTTP_RETRY_COUNT',
            defaultValue: '3',
            description: 'HTTP リトライ回数（Qiita API 取得用）'
        )

        string(
            name: 'MAX_PAGE_COUNT',
            defaultValue: '20',
            description: 'Qiita API の最大ページ取得数（1ページ=100件）'
        )

        booleanParam(
            name: 'DRY_RUN',
            defaultValue: false,
            description: 'true の場合は共有 API に送信せず、収集結果のサマリのみ出力'
        )
    }

    environment {
        QIITA_API_BASE = 'https://qiita.com/api/v2'
    }

    stages {
        stage('Checkout SCM') {
            steps {
                echo 'SCM チェックアウトをスキップします（パイプラインソースはSCMから読み込み済み）。'
            }
        }

        stage('Load Shared Library') {
            steps {
                script {
                    def libBranch = env.CHANGE_BRANCH ?: env.BRANCH_NAME ?: 'main'
                    def libId = "jqit-lib@${libBranch}"

                    try {
                        library libId
                    } catch (err) {
                        echo "${libId} の読み込みに失敗しました。jqit-lib@main へフォールバックします。"
                        library 'jqit-lib@main'
                    }
                }
            }
        }

        stage('Validate Settings') {
            steps {
                script {
                    def credentialIds = qiitaEngagementUtils.parseCsv(params.QIITA_TOKEN_CREDENTIAL_IDS)
                    if (credentialIds.isEmpty()) {
                        error('QIITA_TOKEN_CREDENTIAL_IDS が空です。')
                    }

                    def shareApiUrl = (params.SHARE_API_URL ?: '').trim()
                    if (!shareApiUrl) {
                        error('SHARE_API_URL が空です。')
                    }

                    shareConfig = [
                        credentialIds           : credentialIds,
                        shareApiUrl             : shareApiUrl,
                        shareApiTokenCredentialId: (params.SHARE_API_TOKEN_CREDENTIAL_ID ?: '').trim(),
                        httpTimeoutSec          : qiitaEngagementUtils.toBoundedInt(params.HTTP_TIMEOUT_SEC, 30, 5, 300),
                        httpRetryCount          : qiitaEngagementUtils.toBoundedInt(params.HTTP_RETRY_COUNT, 3, 1, 10),
                        maxPageCount            : qiitaEngagementUtils.toBoundedInt(params.MAX_PAGE_COUNT, 20, 1, 200),
                        dryRun                  : params.DRY_RUN
                    ]

                    echo "対象 Credential IDs: ${shareConfig.credentialIds.join(', ')}"
                    echo "共有先 API URL: ${shareConfig.shareApiUrl}"
                    echo "HTTP タイムアウト: ${shareConfig.httpTimeoutSec}s"
                    echo "HTTP リトライ回数: ${shareConfig.httpRetryCount}"
                    echo "最大ページ数: ${shareConfig.maxPageCount}"
                    echo "DRY_RUN: ${shareConfig.dryRun}"
                }
            }
        }

        stage('Collect Profiles') {
            steps {
                script {
                    def activeAccounts = []

                    for (String credentialId : shareConfig.credentialIds) {
                        try {
                            withCredentials([string(credentialsId: credentialId, variable: 'QIITA_TOKEN')]) {
                                def cfg = [
                                    httpTimeoutSec: shareConfig.httpTimeoutSec,
                                    httpRetryCount: shareConfig.httpRetryCount
                                ]

                                def whoami = qiitaEngagementUtils.qiitaGet(cfg, env.QIITA_API_BASE, '/authenticated_user')
                                if (whoami.code != 200 || !(whoami.body instanceof Map)) {
                                    echo "[WARN] 認証ユーザー取得に失敗したためスキップします: credentialId=${credentialId}, HTTP=${whoami.code}"
                                } else {
                                    def userId = (whoami.body.id ?: '').toString().trim()
                                    if (!userId) {
                                        echo "[WARN] userId が空のためスキップします: credentialId=${credentialId}"
                                    } else {
                                        def followees = collectPaged(cfg, "/authenticated_user/followees", shareConfig.maxPageCount)
                                        def followingTags = collectPaged(cfg, '/authenticated_user/following_tags', shareConfig.maxPageCount)

                                        def questionsRes = qiitaEngagementUtils.qiitaGet(cfg, env.QIITA_API_BASE, "/users/${userId}/questions?page=1&per_page=100")
                                        def questions = []
                                        if (questionsRes.code == 200 && (questionsRes.body instanceof List)) {
                                            questions = questionsRes.body.collect { q ->
                                                [
                                                    id       : (q?.id ?: '').toString(),
                                                    title    : (q?.title ?: '').toString(),
                                                    url      : (q?.url ?: '').toString(),
                                                    createdAt: (q?.created_at ?: '').toString()
                                                ]
                                            }
                                        } else {
                                            echo "[WARN] 質問一覧の取得に失敗または未対応のため空配列で続行します: credentialId=${credentialId}, HTTP=${questionsRes.code}"
                                        }

                                        activeAccounts << [
                                            credentialId : credentialId,
                                            userId       : userId,
                                            followees    : followees.collect { u ->
                                                [
                                                    id      : (u?.id ?: '').toString(),
                                                    name    : (u?.name ?: '').toString(),
                                                    profile : (u?.profile_image_url ?: '').toString()
                                                ]
                                            },
                                            followingTags: followingTags.collect { t ->
                                                [
                                                    id      : (t?.id ?: '').toString(),
                                                    iconUrl : (t?.icon_url ?: '').toString(),
                                                    items   : (t?.items_count ?: 0) as Integer
                                                ]
                                            },
                                            questions    : questions
                                        ]

                                        echo "収集完了: credentialId=${credentialId}, userId=${userId}, followees=${followees.size()}, tags=${followingTags.size()}, questions=${questions.size()}"
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            echo "[WARN] Credential 処理に失敗したためスキップします: credentialId=${credentialId}, reason=${ex.message}"
                        }
                    }

                    if (activeAccounts.isEmpty()) {
                        error('共有対象の有効アカウントを1件も収集できませんでした。')
                    }

                    def payload = [
                        source      : 'jenkins-qiita-profile-share',
                        generatedAt : new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone('Asia/Tokyo')),
                        accountCount : activeAccounts.size(),
                        accounts    : activeAccounts
                    ]

                    shareConfig.payload = payload
                    writeFile(file: 'qiita-share-payload.json', text: groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(payload)))
                    echo "共有ペイロードを作成しました: accountCount=${activeAccounts.size()}"
                }
            }
        }

        stage('Share To API') {
            steps {
                script {
                    if (shareConfig.dryRun) {
                        echo '[DRY_RUN] 共有 API への送信をスキップしました。'
                        return
                    }

                    def payloadJson = writeJSON(returnText: true, json: shareConfig.payload)
                    String apiUrl = shareConfig.shareApiUrl
                    String timeoutSec = "${shareConfig.httpTimeoutSec}"

                    if (shareConfig.shareApiTokenCredentialId) {
                        withCredentials([string(credentialsId: shareConfig.shareApiTokenCredentialId, variable: 'SHARE_API_TOKEN')]) {
                            sh """
                                set -euo pipefail
                                curl -sS --fail \
                                  --connect-timeout ${timeoutSec} \
                                  --max-time ${timeoutSec} \
                                  -X POST \
                                  -H 'Content-Type: application/json' \
                                  -H "Authorization: Bearer \$SHARE_API_TOKEN" \
                                  --data '${qiitaEngagementUtils.shellQuote(payloadJson)}' \
                                  '${qiitaEngagementUtils.shellQuote(apiUrl)}' >/tmp/qiita_share_api_result.txt
                            """
                        }
                    } else {
                        sh """
                            set -euo pipefail
                            curl -sS --fail \
                              --connect-timeout ${timeoutSec} \
                              --max-time ${timeoutSec} \
                              -X POST \
                              -H 'Content-Type: application/json' \
                              --data '${qiitaEngagementUtils.shellQuote(payloadJson)}' \
                              '${qiitaEngagementUtils.shellQuote(apiUrl)}' >/tmp/qiita_share_api_result.txt
                        """
                    }

                    echo '共有 API への送信が完了しました。'
                }
            }
        }
    }

    post {
        success {
            echo 'Qiita プロファイル共有パイプラインが正常に完了しました。'
        }
        failure {
            echo 'Qiita プロファイル共有パイプラインが失敗しました。設定とログを確認してください。'
        }
        always {
            script {
                sh '''
                    rm -f /tmp/qiita_share_api_result.txt 2>/dev/null || true
                '''
            }
        }
    }
}

/*
 * ページング付きで Qiita API の配列レスポンスを取得する。
 * page=1..maxPageCount を順にたどり、空配列または 100 件未満で停止する。
 */
def collectPaged(Map cfg, String apiPath, int maxPageCount) {
    def all = []
    int perPage = 100

    for (int page = 1; page <= maxPageCount; page++) {
        String sep = apiPath.contains('?') ? '&' : '?'
        String path = "${apiPath}${sep}page=${page}&per_page=${perPage}"

        def res = qiitaEngagementUtils.qiitaGet(cfg, env.QIITA_API_BASE, path)
        if (res.code != 200) {
            echo "[WARN] ページ取得失敗: path=${apiPath}, page=${page}, HTTP=${res.code}"
            break
        }

        if (!(res.body instanceof List)) {
            echo "[WARN] ページレスポンス形式が不正です: path=${apiPath}, page=${page}"
            break
        }

        def chunk = res.body
        if (chunk.isEmpty()) {
            break
        }

        all.addAll(chunk)

        if (chunk.size() < perPage) {
            break
        }
    }

    return all
}
