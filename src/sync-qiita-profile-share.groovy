import groovy.transform.Field

/*
 * ============================================================
 * Qiita フォロー同期 Pipeline
 * ============================================================
 *
 * 目的:
 * - 複数 Qiita アカウントの「フォロー中ユーザー」「フォロー中タグ」を収集し、
 *   各アカウントへ相互反映する（A/B/C の差分を埋める）。
 * - さらに、本パイプラインに登録されたアカウント同士を相互フォローさせる。
 *   （A/B/C を登録 → B/C が A を、A/C が B を、A/B が C をフォロー。トークン
 *    ユーザーが増えれば対象も自動拡張）
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
     * 定期実行: 毎日1回（4時台、分はHで分散）。
     * 30分おきに走る update-qiita-engagement と被らない時間帯に置く。
     */
    triggers {
        cron('TZ=Asia/Tokyo\nH 4 * * *')
    }

    options {
        skipDefaultCheckout(true)
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    parameters {
        string(
            name: 'QIITA_TOKEN_CREDENTIAL_IDS',
            defaultValue: 'jqit-qiita-access-token,personal-qiita-access-token,lberc-qiita-access-token,honoka-qiita-access-token,umi-qiita-access-token,kotori-qiita-access-token,nico-qiita-access-token,eri-qiita-access-token,nozomi-qiita-access-token,maki-qiita-access-token,rin-qiita-access-token,hanayo-qiita-access-token,anju-qiita-access-token,erena-qiita-access-token,tsubasa-qiita-access-token',
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
            defaultValue: '100',
            description: 'Qiita API の最大ページ取得数（1ページ=100件）'
        )

        string(
            name: 'API_THROTTLE_MS',
            defaultValue: '2000',
            description: '各書き込み API 呼び出しの間に挿入する待機ミリ秒。Qiita の spam 検知で silent drop を避けるために多めに取る'
        )

        string(
            name: 'MAX_FOLLOWS_PER_RUN',
            defaultValue: '30',
            description: '1 run / 1 アカウントあたりのユーザーフォロー PUT 上限。0 で無制限。spam 判定回避のため複数日に分けて埋める用途'
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

        stage('Load Shared Library') {
            when {
                beforeAgent true
                expression { return env.CONCURRENCY_SKIP != 'true' }
            }
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
            when {
                beforeAgent true
                expression { return env.CONCURRENCY_SKIP != 'true' }
            }
            steps {
                script {
                    def credentialIds = qiitaEngagementUtils.parseCsv(params.QIITA_TOKEN_CREDENTIAL_IDS)
                    if (credentialIds.isEmpty()) {
                        error('QIITA_TOKEN_CREDENTIAL_IDS が空です。')
                    }

                    syncConfig = [
                        credentialIds    : credentialIds,
                        httpTimeoutSec   : qiitaEngagementUtils.toBoundedInt(params.HTTP_TIMEOUT_SEC, 30, 5, 300),
                        httpRetryCount   : qiitaEngagementUtils.toBoundedInt(params.HTTP_RETRY_COUNT, 3, 1, 10),
                        maxPageCount     : qiitaEngagementUtils.toBoundedInt(params.MAX_PAGE_COUNT, 100, 1, 200),
                        apiThrottleMs    : qiitaEngagementUtils.toBoundedInt(params.API_THROTTLE_MS, 2000, 0, 30000),
                        maxFollowsPerRun : qiitaEngagementUtils.toBoundedInt(params.MAX_FOLLOWS_PER_RUN, 30, 0, 10000),
                        dryRun           : params.DRY_RUN
                    ]

                    echo "対象 Credential IDs: ${syncConfig.credentialIds.join(', ')}"
                    echo "HTTP タイムアウト: ${syncConfig.httpTimeoutSec}s"
                    echo "HTTP リトライ回数: ${syncConfig.httpRetryCount}"
                    echo "最大ページ数: ${syncConfig.maxPageCount}"
                    echo "API スロットル: ${syncConfig.apiThrottleMs}ms"
                    echo "1 run のフォロー上限/アカウント: ${syncConfig.maxFollowsPerRun == 0 ? '無制限' : syncConfig.maxFollowsPerRun}"
                    echo "DRY_RUN: ${syncConfig.dryRun}"
                }
            }
        }

        stage('Collect Profiles') {
            when {
                beforeAgent true
                expression { return env.CONCURRENCY_SKIP != 'true' }
            }
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

                    // 既存のフォロー先 union に加え、本パイプライン参加アカウントの userId も
                    // ターゲットへ含める。これにより A/B/C を登録すると相互にフォローし合う。
                    // 各アカウントの「自分自身」は Sync ステージで除外されるため、結果的に
                    // 自分以外の参加メンバー全員をフォローする形になる。
                    def memberUserIds = activeAccounts.collect { it.userId }.findAll { it }.unique()
                    def unionFollowees = (activeAccounts.collectMany { it.followeeIds ?: [] } + memberUserIds).findAll { it }.unique()
                    def unionTags = activeAccounts.collectMany { it.tagIds ?: [] }.findAll { it }.unique()

                    syncConfig.activeAccounts = activeAccounts
                    syncConfig.memberUserIds = memberUserIds
                    syncConfig.unionFollowees = unionFollowees
                    syncConfig.unionTags = unionTags

                    echo "収集サマリ: accounts=${activeAccounts.size()}, members=${memberUserIds.size()} (${memberUserIds.join(', ')}), unionFollowees=${unionFollowees.size()}, unionTags=${unionTags.size()}"
                }
            }
        }

        stage('Sync Followees And Tags') {
            when {
                beforeAgent true
                expression { return env.CONCURRENCY_SKIP != 'true' }
            }
            steps {
                script {
                    int appliedUserFollows = 0
                    int appliedTagFollows = 0
                    int skipped = 0
                    int failures = 0
                    int throttleMs = syncConfig.apiThrottleMs ?: 0
                    int maxFollowsPerRun = syncConfig.maxFollowsPerRun ?: 0
                    int deferredFollows = 0

                    for (def account : syncConfig.activeAccounts) {
                        def credentialId = account.credentialId
                        def selfUserId = account.userId
                        def ownFolloweeIds = (account.followeeIds ?: []) as Set
                        def ownTagIds = (account.tagIds ?: []) as Set

                        def missingFollowees = ((syncConfig.unionFollowees as Set) - ownFolloweeIds - ([selfUserId] as Set)) as List

                        // 1 run / 1 アカウントあたりの follow 上限。Qiita の spam 判定回避目的。
                        // 上限を超えた分は次回 run に持ち越す（冪等なので自然に解決）。
                        def followeesToProcess = missingFollowees
                        int deferredThisAccount = 0
                        if (maxFollowsPerRun > 0 && missingFollowees.size() > maxFollowsPerRun) {
                            followeesToProcess = missingFollowees.take(maxFollowsPerRun)
                            deferredThisAccount = missingFollowees.size() - maxFollowsPerRun
                            deferredFollows += deferredThisAccount
                        }
                        def missingTags = (syncConfig.unionTags as Set) - ownTagIds

                        echo "同期対象: credentialId=${credentialId}, userId=${selfUserId}, 追加予定 followees=${followeesToProcess.size()}/${missingFollowees.size()}, tags=${missingTags.size()}" + (deferredThisAccount > 0 ? " (次回持ち越し=${deferredThisAccount})" : '')

                        withCredentials([string(credentialsId: credentialId, variable: 'QIITA_TOKEN')]) {
                            def cfg = [
                                httpTimeoutSec: syncConfig.httpTimeoutSec,
                                httpRetryCount: syncConfig.httpRetryCount
                            ]

                            for (String targetUserId : followeesToProcess) {
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

                                if (throttleMs > 0) {
                                    sleep time: throttleMs, unit: 'MILLISECONDS'
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

                                if (throttleMs > 0) {
                                    sleep time: throttleMs, unit: 'MILLISECONDS'
                                }
                            }
                        }
                    }

                    echo '同期結果:'
                    echo "  ユーザーフォロー反映数 = ${appliedUserFollows}"
                    echo "  タグフォロー反映数   = ${appliedTagFollows}"
                    echo "  次回持ち越し       = ${deferredFollows}"
                    echo "  DRY_RUNスキップ数    = ${skipped}"
                    echo "  失敗数              = ${failures}"

                    if (failures > 0) {
                        currentBuild.result = 'UNSTABLE'
                        echo '[WARN] 一部反映に失敗したため、ビルド結果を UNSTABLE に設定します。'
                    }
                }
            }
        }

        stage('Verify Sync Results') {
            when {
                beforeAgent true
                expression { return env.CONCURRENCY_SKIP != 'true' && !syncConfig.dryRun }
            }
            steps {
                script {
                    // 書き込み直後に followees を再取得し、union との差分（＝Qiita 側で
                    // silent drop されている可能性のある follow 件数）を可視化する。
                    // ここでは自動再試行は行わない。冪等構造のため次回 run で自然に拾い直す。
                    def unionSet = (syncConfig.unionFollowees ?: []) as Set
                    int totalGap = 0

                    for (def account : syncConfig.activeAccounts) {
                        def credentialId = account.credentialId
                        def selfUserId = account.userId

                        withCredentials([string(credentialsId: credentialId, variable: 'QIITA_TOKEN')]) {
                            def cfg = [
                                httpTimeoutSec: syncConfig.httpTimeoutSec,
                                httpRetryCount: syncConfig.httpRetryCount
                            ]
                            def latestFollowees = collectPaged(cfg, "/users/${selfUserId}/followees", syncConfig.maxPageCount)
                            def latestIds = latestFollowees.collect { (it?.id ?: '').toString().trim() }.findAll { it } as Set
                            def gap = (unionSet - latestIds - ([selfUserId] as Set))
                            totalGap += gap.size()

                            if (gap.isEmpty()) {
                                echo "検証 OK: credentialId=${credentialId}, userId=${selfUserId}, followees=${latestIds.size()} (union=${unionSet.size()})"
                            } else {
                                def sample = gap.take(10).join(', ')
                                def suffix = gap.size() > 10 ? ' …他' : ''
                                echo "[WARN] 同期検証で差分検出: credentialId=${credentialId}, userId=${selfUserId}, followees=${latestIds.size()}, gap=${gap.size()} (例: ${sample}${suffix})"
                            }
                        }
                    }

                    if (totalGap > 0) {
                        echo "未反映 follow 総数: ${totalGap}（次回 run で再試行されます）"
                    } else {
                        echo '全アカウントで follow が union と一致しました。'
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
