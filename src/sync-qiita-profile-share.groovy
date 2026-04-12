import groovy.transform.Field

/*
 * ============================================================
 * Qiita フォロー同期 Pipeline
 * ============================================================
 *
 * 目的:
 * - 複数 Qiita アカウントの「フォロー中ユーザー」「フォロー中タグ」を収集し、
 *   各アカウントへ相互反映する（A/B/C の差分を埋める）。
 *
 * 前提:
 * - Jenkins Secret text credential に Qiita API トークンを登録済み
 * - 各トークンに follow 権限があること
 */

@Field def syncConfig = [:]

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
            description: 'true の場合は同期 API 更新をせず、差分サマリのみ出力'
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

                    syncConfig = [
                        credentialIds : credentialIds,
                        httpTimeoutSec: qiitaEngagementUtils.toBoundedInt(params.HTTP_TIMEOUT_SEC, 30, 5, 300),
                        httpRetryCount: qiitaEngagementUtils.toBoundedInt(params.HTTP_RETRY_COUNT, 3, 1, 10),
                        maxPageCount  : qiitaEngagementUtils.toBoundedInt(params.MAX_PAGE_COUNT, 20, 1, 200),
                        dryRun        : params.DRY_RUN
                    ]

                    echo "対象 Credential IDs: ${syncConfig.credentialIds.join(', ')}"
                    echo "HTTP タイムアウト: ${syncConfig.httpTimeoutSec}s"
                    echo "HTTP リトライ回数: ${syncConfig.httpRetryCount}"
                    echo "最大ページ数: ${syncConfig.maxPageCount}"
                    echo "DRY_RUN: ${syncConfig.dryRun}"
                }
            }
        }

        stage('Collect Profiles') {
            steps {
                script {
                    def activeAccounts = []

                    for (String credentialId : syncConfig.credentialIds) {
                        try {
                            withCredentials([string(credentialsId: credentialId, variable: 'QIITA_TOKEN')]) {
                                def cfg = [
                                    httpTimeoutSec: syncConfig.httpTimeoutSec,
                                    httpRetryCount: syncConfig.httpRetryCount
                                ]

                                def whoami = qiitaEngagementUtils.qiitaGet(cfg, env.QIITA_API_BASE, '/authenticated_user')
                                if (whoami.code != 200 || !(whoami.body instanceof Map)) {
                                    echo "[WARN] 認証ユーザー取得に失敗したためスキップします: credentialId=${credentialId}, HTTP=${whoami.code}"
                                } else {
                                    def userId = (whoami.body.id ?: '').toString().trim()
                                    if (!userId) {
                                        echo "[WARN] userId が空のためスキップします: credentialId=${credentialId}"
                                    } else {
                                        def followees = collectPaged(cfg, "/users/${userId}/followees", syncConfig.maxPageCount)
                                        def followingTags = collectPaged(cfg, "/users/${userId}/following_tags", syncConfig.maxPageCount)

                                        activeAccounts << [
                                            credentialId : credentialId,
                                            userId       : userId,
                                            followeeIds  : followees.collect { (it?.id ?: '').toString().trim() }.findAll { it }.unique(),
                                            tagIds       : followingTags.collect { (it?.id ?: '').toString().trim() }.findAll { it }.unique()
                                        ]

                                        echo "収集完了: credentialId=${credentialId}, userId=${userId}, followees=${followees.size()}, tags=${followingTags.size()}"
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            echo "[WARN] Credential 処理に失敗したためスキップします: credentialId=${credentialId}, reason=${ex.message}"
                        }
                    }

                    if (activeAccounts.isEmpty()) {
                        error('同期対象の有効アカウントを1件も収集できませんでした。')
                    }

                    def unionFollowees = activeAccounts.collectMany { it.followeeIds ?: [] }.findAll { it }.unique()
                    def unionTags = activeAccounts.collectMany { it.tagIds ?: [] }.findAll { it }.unique()

                    syncConfig.activeAccounts = activeAccounts
                    syncConfig.unionFollowees = unionFollowees
                    syncConfig.unionTags = unionTags

                    echo "収集サマリ: accounts=${activeAccounts.size()}, unionFollowees=${unionFollowees.size()}, unionTags=${unionTags.size()}"
                }
            }
        }

        stage('Sync Followees And Tags') {
            steps {
                script {
                    int appliedUserFollows = 0
                    int appliedTagFollows = 0
                    int skipped = 0
                    int failures = 0

                    for (def account : syncConfig.activeAccounts) {
                        def credentialId = account.credentialId
                        def selfUserId = account.userId
                        def ownFolloweeIds = (account.followeeIds ?: []) as Set
                        def ownTagIds = (account.tagIds ?: []) as Set

                        def missingFollowees = (syncConfig.unionFollowees as Set) - ownFolloweeIds - ([selfUserId] as Set)
                        def missingTags = (syncConfig.unionTags as Set) - ownTagIds

                        echo "同期対象: credentialId=${credentialId}, userId=${selfUserId}, 追加予定 followees=${missingFollowees.size()}, tags=${missingTags.size()}"

                        withCredentials([string(credentialsId: credentialId, variable: 'QIITA_TOKEN')]) {
                            def cfg = [
                                httpTimeoutSec: syncConfig.httpTimeoutSec,
                                httpRetryCount: syncConfig.httpRetryCount
                            ]

                            for (String targetUserId : missingFollowees) {
                                if (syncConfig.dryRun) {
                                    echo "[DRY_RUN] ユーザーフォロー追加予定: credentialId=${credentialId}, targetUser=${targetUserId}"
                                    skipped++
                                    continue
                                }

                                def res = qiitaPutWithFallback(cfg, [
                                    "/users/${targetUserId}/following",
                                    "/users/${targetUserId}/follow"
                                ])

                                if (qiitaEngagementUtils.isSuccessCode(res.code) || res.code == 409) {
                                    appliedUserFollows++
                                } else {
                                    failures++
                                    echo "[WARN] ユーザーフォロー反映失敗: credentialId=${credentialId}, targetUser=${targetUserId}, HTTP=${res.code}, path=${res.path}, body=${qiitaEngagementUtils.trimForLog(res.rawBody)}"
                                }
                            }

                            for (String tagId : missingTags) {
                                if (syncConfig.dryRun) {
                                    echo "[DRY_RUN] タグフォロー追加予定: credentialId=${credentialId}, tag=${tagId}"
                                    skipped++
                                    continue
                                }

                                // タグIDに '#' 等が含まれる場合はURLエンコードが必要（例: C#→C%23）
                                def encodedTagId = URLEncoder.encode(tagId, 'UTF-8')
                                def res = qiitaPutWithFallback(cfg, [
                                    "/tags/${encodedTagId}/following",
                                    "/tags/${encodedTagId}/follow"
                                ])

                                if (qiitaEngagementUtils.isSuccessCode(res.code) || res.code == 409) {
                                    appliedTagFollows++
                                } else {
                                    failures++
                                    echo "[WARN] タグフォロー反映失敗: credentialId=${credentialId}, tag=${tagId}, HTTP=${res.code}, path=${res.path}, body=${qiitaEngagementUtils.trimForLog(res.rawBody)}"
                                }
                            }
                        }
                    }

                    echo '同期結果:'
                    echo "  ユーザーフォロー反映数 = ${appliedUserFollows}"
                    echo "  タグフォロー反映数   = ${appliedTagFollows}"
                    echo "  DRY_RUNスキップ数    = ${skipped}"
                    echo "  失敗数              = ${failures}"

                    if (failures > 0) {
                        currentBuild.result = 'UNSTABLE'
                        echo '[WARN] 一部反映に失敗したため、ビルド結果を UNSTABLE に設定します。'
                    }
                }
            }
        }
    }

    post {
        success {
            echo 'Qiita フォロー同期パイプラインが正常に完了しました。'
        }
        failure {
            echo 'Qiita フォロー同期パイプラインが失敗しました。設定とログを確認してください。'
        }
    }
}

/*
 * PUT エンドポイント候補を順に試し、成功または非404が出た時点で返す。
 */
def qiitaPutWithFallback(Map cfg, List<String> apiPaths) {
    def last = [code: 0, body: '', rawBody: '', headers: '', path: '']

    for (String apiPath : (apiPaths ?: [])) {
        def res = qiitaEngagementUtils.qiitaPut(cfg, env.QIITA_API_BASE, apiPath)
        last = (res ?: [:]) + [path: apiPath]

        if (qiitaEngagementUtils.isSuccessCode(res.code) || res.code == 409) {
            return last
        }

        if (res.code != 404) {
            return last
        }
    }

    return last
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
