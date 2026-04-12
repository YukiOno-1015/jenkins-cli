/*
 * Qiita の Organization 投稿を定期監視し、新着記事へ自動で「いいね」と
 * 「ストック（あとで読む）」を付与する Declarative Pipeline です。
 *
 * 運用方針:
 * - 監視対象は `organization_url_name` でフィルタする
 * - 処理済み記事IDを state に保存し、重複実行を避ける
 * - DRY_RUN=true で API 書き込みを行わず、対象抽出のみ検証できる
 *
 * 必要な Jenkins Credentials（Kind: Secret text）:
 *   qiita-access-token : Qiita API Token（write_qiita を含む）
 */

def qiitaConfig = [:]

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

    options {
        skipDefaultCheckout(true)
        timestamps()
        disableConcurrentBuilds()
    }

    parameters {
        string(name: 'TARGET_ORGANIZATIONS', defaultValue: 'jqiit-co,lberc', description: '監視対象 organization_url_name（カンマ区切り）')
        booleanParam(name: 'DO_LIKE', defaultValue: true, description: 'true の場合、記事へ「いいね」を付与する')
        booleanParam(name: 'DO_STOCK', defaultValue: true, description: 'true の場合、記事をストックする')
        booleanParam(name: 'DRY_RUN', defaultValue: false, description: 'true の場合、実 API 更新をせず対象抽出のみ行う')
        string(name: 'PER_PAGE', defaultValue: '50', description: '1ページ取得件数（1-100）')
        string(name: 'MAX_PAGES', defaultValue: '3', description: '取得ページ上限（1-100）')
        string(name: 'STATE_FILE_PATH', defaultValue: '', description: '処理済み記事IDの保存先。空欄時は $HOME/.qiita-auto-engagement/processed_item_ids.txt を利用')
        string(name: 'QIITA_TOKEN_CREDENTIAL_ID', defaultValue: 'qiita-access-token', description: 'Qiita API Token の Jenkins Credential ID')
    }

    environment {
        QIITA_API_BASE = 'https://qiita.com/api/v2'
        STATE_DIR      = "${HOME}/.qiita-auto-engagement"
        STATE_FILE     = "${HOME}/.qiita-auto-engagement/processed_item_ids.txt"
        MAX_STATE_IDS  = '5000'
    }

    stages {
        stage('Checkout SCM') {
            steps {
                echo 'Skipping explicit checkout scm (pipeline source is already loaded from SCM).'
            }
        }

        stage('Validate Settings') {
            steps {
                script {
                    def organizations = (params.TARGET_ORGANIZATIONS ?: '')
                        .split(',')
                        .collect { it.trim() }
                        .findAll { it }
                        .unique()

                    if (organizations.isEmpty()) {
                        error('TARGET_ORGANIZATIONS is empty. Set at least one organization_url_name.')
                    }
                    if (!params.DO_LIKE && !params.DO_STOCK) {
                        error('Both DO_LIKE and DO_STOCK are false. Enable at least one action.')
                    }

                    int perPage = toBoundedInt(params.PER_PAGE, 50, 1, 100)
                    int maxPages = toBoundedInt(params.MAX_PAGES, 3, 1, 100)
                    def defaultStateFile = "${env.HOME}/.qiita-auto-engagement/processed_item_ids.txt"
                    def stateFilePath = (params.STATE_FILE_PATH ?: '').trim() ?: defaultStateFile
                    def stateDirPath = sh(
                        script: "dirname '${stateFilePath}'",
                        returnStdout: true
                    ).trim()

                    qiitaConfig = [
                        organizations: organizations,
                        doLike: params.DO_LIKE,
                        doStock: params.DO_STOCK,
                        dryRun: params.DRY_RUN,
                        perPage: perPage,
                        maxPages: maxPages,
                        stateFile: stateFilePath,
                        stateDir: stateDirPath,
                        credentialId: (params.QIITA_TOKEN_CREDENTIAL_ID ?: '').trim(),
                    ]

                    if (!qiitaConfig.credentialId) {
                        error('QIITA_TOKEN_CREDENTIAL_ID is empty.')
                    }

                    echo "Organizations: ${qiitaConfig.organizations.join(', ')}"
                    echo "Actions: like=${qiitaConfig.doLike}, stock=${qiitaConfig.doStock}, dryRun=${qiitaConfig.dryRun}"
                    echo "Paging: perPage=${qiitaConfig.perPage}, maxPages=${qiitaConfig.maxPages}"
                    echo "State file: ${qiitaConfig.stateFile}"
                    echo "Credential: ${qiitaConfig.credentialId}"
                }
            }
        }

        stage('Verify Qiita Token') {
            steps {
                script {
                    withCredentials([string(credentialsId: qiitaConfig.credentialId, variable: 'QIITA_TOKEN')]) {
                        def whoami = qiitaGet('/authenticated_user')
                        if (whoami.code != 200) {
                            error("Qiita token verification failed: HTTP ${whoami.code}\n${whoami.raw}")
                        }
                        echo "Authenticated as: ${whoami.body?.id ?: 'unknown'}"
                    }
                }
            }
        }

        stage('Monitor & Engage') {
            steps {
                script {
                    sh "mkdir -p '${qiitaConfig.stateDir}'"
                    def stateText = sh(script: "cat '${qiitaConfig.stateFile}' 2>/dev/null || true", returnStdout: true).trim()
                    def existingIds = stateText ? stateText.readLines().collect { it.trim() }.findAll { it } : []
                    def existingSet = existingIds as Set

                    def targets = []
                    withCredentials([string(credentialsId: qiitaConfig.credentialId, variable: 'QIITA_TOKEN')]) {
                        // Qiita API v2 には Organization 記事一覧エンドポイントが存在しないため、
                        // Organization の記事一覧ページ (HTML) を取得して記事 ID を抽出し、
                        // 各記事の詳細を API で補完する方式を使用する。
                        for (def orgName in qiitaConfig.organizations) {
                            for (int page = 1; page <= qiitaConfig.maxPages; page++) {
                                // HTML ページから /username/items/ITEMID 形式の記事 ID を抽出する
                                // Qiita の記事 ID は 20 桁の英数字（16進数）
                                def itemIds = sh(
                                    returnStdout: true,
                                    script: """curl -s \
                                        -H 'Authorization: Bearer \$QIITA_TOKEN' \
                                        'https://qiita.com/organizations/${orgName}/items?page=${page}' \
                                        | grep -oE 'href="/[^/]+/items/[0-9a-f]{20}"' \
                                        | grep -oE '[0-9a-f]{20}' \
                                        | sort -u || true"""
                                ).trim().readLines().findAll { it }

                                if (itemIds.isEmpty()) {
                                    break
                                }

                                echo "HTMLスクレイピング: page=${page} → ${itemIds.size()} 件検出 (org: ${orgName})"

                                for (def itemId in itemIds) {
                                    if (existingSet.contains(itemId)) {
                                        continue
                                    }

                                    def itemRes = qiitaGet("/items/${itemId}")
                                    if (itemRes.code != 200) {
                                        echo "[WARN] 記事詳細の取得に失敗 (id: ${itemId}): HTTP ${itemRes.code}"
                                        continue
                                    }

                                    def item = itemRes.body
                                    targets << [
                                        id: itemId,
                                        title: (item.title ?: '').toString(),
                                        url: (item.url ?: '').toString(),
                                        org: orgName,
                                        createdAt: (item.created_at ?: '').toString(),
                                    ]
                                }
                            }
                        }

                        if (targets.isEmpty()) {
                            echo 'No new organization posts detected.'
                            return
                        }

                        // 先に投稿された記事から順に処理する
                        targets = targets.sort { a, b ->
                            (a.createdAt ?: '') <=> (b.createdAt ?: '')
                        }

                        int likedCount = 0
                        int stockedCount = 0
                        int failedCount = 0
                        def processedIds = []

                        for (def target in targets) {
                            echo "Target: [${target.org}] ${target.title} (${target.id})"

                            boolean likeOk = !qiitaConfig.doLike
                            boolean stockOk = !qiitaConfig.doStock

                            if (qiitaConfig.dryRun) {
                                echo "DRY_RUN: skip actions for ${target.url}"
                                likeOk = true
                                stockOk = true
                            } else {
                                if (qiitaConfig.doLike) {
                                    def likeRes = qiitaPut("/items/${target.id}/like")
                                    likeOk = isSuccessCode(likeRes.code)
                                    if (likeOk) {
                                        likedCount++
                                    } else {
                                        echo "[WARN] like failed (${target.id}): HTTP ${likeRes.code} ${likeRes.raw}"
                                    }
                                }

                                if (qiitaConfig.doStock) {
                                    def stockRes = qiitaPut("/items/${target.id}/stock")
                                    stockOk = isSuccessCode(stockRes.code)
                                    if (stockOk) {
                                        stockedCount++
                                    } else {
                                        echo "[WARN] stock failed (${target.id}): HTTP ${stockRes.code} ${stockRes.raw}"
                                    }
                                }
                            }

                            if (likeOk && stockOk) {
                                processedIds << target.id
                            } else {
                                failedCount++
                            }
                        }

                        def merged = []
                        merged.addAll(processedIds)
                        merged.addAll(existingIds)
                        merged = merged.findAll { it }.unique().take(toBoundedInt(env.MAX_STATE_IDS, 5000, 100, 20000))

                        writeFile(file: qiitaConfig.stateFile, text: merged.join('\n') + (merged.isEmpty() ? '' : '\n'))

                        echo "Summary: targets=${targets.size()}, liked=${likedCount}, stocked=${stockedCount}, failed=${failedCount}, stateSize=${merged.size()}"

                        if (failedCount > 0) {
                            currentBuild.result = 'UNSTABLE'
                            echo 'Some actions failed. Marking build as UNSTABLE.'
                        }
                    }
                }
            }
        }
    }

    post {
        success { echo 'Qiita engagement pipeline completed successfully' }
        unstable { echo 'Qiita engagement pipeline completed with partial failures (UNSTABLE)' }
        failure { echo 'Qiita engagement pipeline failed. Check console log for details.' }
    }
}

def isSuccessCode(int code) {
    return [200, 201, 204].contains(code)
}

def toBoundedInt(def raw, int defaultValue, int min, int max) {
    int value
    try {
        value = (raw?.toString()?.trim() ?: defaultValue.toString()) as Integer
    } catch (Exception ignored) {
        value = defaultValue
    }
    return Math.max(min, Math.min(max, value))
}

def qiitaGet(String apiPath) {
    return qiitaRequest('GET', apiPath)
}

def qiitaPut(String apiPath) {
    return qiitaRequest('PUT', apiPath)
}

def qiitaRequest(String method, String apiPath) {
    def raw = sh(
        script: """curl -s -w '\\n%{http_code}' \\
            -X ${method} \\
            -H "Authorization: Bearer \$QIITA_TOKEN" \\
            '${env.QIITA_API_BASE}${apiPath}'""",
        returnStdout: true
    ).trim()

    return parseResponse(raw)
}

def parseResponse(String raw) {
    def lines = raw.readLines()
    if (lines.isEmpty()) {
        return [code: 0, body: [:], raw: raw]
    }

    int code = (lines.last() ?: '0').toInteger()
    def bodyText = lines.size() > 1 ? lines[0..-2].join('\n') : ''

    def body = [:]
    if (bodyText?.trim()) {
        body = readJSON(text: bodyText)
    }

    return [code: code, body: body, raw: bodyText]
}
