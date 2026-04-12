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
        string(name: 'TARGET_ORGANIZATIONS', defaultValue: 'jqiit-co', description: '監視対象 organization_url_name（カンマ区切り）')
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
                        for (int page = 1; page <= qiitaConfig.maxPages; page++) {
                            def res = qiitaGet("/items?page=${page}&per_page=${qiitaConfig.perPage}")
                            if (res.code != 200) {
                                error("Failed to fetch items: HTTP ${res.code}\n${res.raw}")
                            }

                            def items = (res.body instanceof List) ? res.body : []
                            if (items.isEmpty()) {
                                break
                            }

                            for (def item in items) {
                                def org = (item.organization_url_name ?: '').toString()
                                if (!qiitaConfig.organizations.contains(org)) {
                                    continue
                                }

                                def itemId = (item.id ?: '').toString()
                                if (!itemId || existingSet.contains(itemId)) {
                                    continue
                                }

                                targets << [
                                    id: itemId,
                                    title: (item.title ?: '').toString(),
                                    url: (item.url ?: '').toString(),
                                    org: org,
                                    createdAt: (item.created_at ?: '').toString(),
                                ]
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
