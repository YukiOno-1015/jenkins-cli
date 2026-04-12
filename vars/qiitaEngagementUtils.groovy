import com.cloudbees.groovy.cps.NonCPS

/*
 * Qiita エンゲージメント系パイプラインで再利用する共通ユーティリティ。
 * Jenkins Shared Library の vars として、
 * `qiitaEngagementUtils.methodName(...)` で呼び出す。
 */

/*
 * カンマ区切り文字列を配列へ変換する。
 * 余計な空白を除去し、空要素を捨て、重複を排除する。
 */
def parseCsv(String raw) {
    return (raw ?: '')
        .split(',')
        .collect { it.trim() }
        .findAll { it }
        .unique()
}

/*
 * 任意入力を int に変換し、最小値/最大値で丸める。
 */
def toBoundedInt(def raw, int defaultValue, int min, int max) {
    int value
    try {
        value = (raw?.toString()?.trim() ?: defaultValue.toString()) as Integer
    } catch (Exception ignored) {
        value = defaultValue
    }
    return Math.max(min, Math.min(max, value))
}

/*
 * シェルのシングルクォート埋め込み対策。
 */
def shellQuote(String value) {
    return (value ?: '').replace("'", "'\"'\"'")
}

/*
 * ログ出力用に長い本文を切り詰める。
 */
def trimForLog(String s, int maxLen = 800) {
    def text = (s ?: '').trim()
    if (text.length() <= maxLen) {
        return text
    }
    return text.substring(0, maxLen) + ' ...<trimmed>'
}

/*
 * 成功扱い HTTP code 判定
 */
def isSuccessCode(int code) {
    return [200, 201, 204].contains(code)
}

/*
 * Qiita は既に like 済みの場合に 403 + type=already_liked を返す。
 * 冪等実行のため成功扱いにする。
 */
def isAlreadyLikeResponse(def res) {
    if ((res?.code ?: 0) != 403) {
        return false
    }

    def bodyType = (res?.body instanceof Map) ? (res.body.type ?: '').toString() : ''
    return bodyType == 'already_liked'
}

/*
 * Qiita は既に stock 済みの場合に 403 + type=already_stocked を返す。
 * 冪等実行のため成功扱いにする。
 */
def isAlreadyStockResponse(def res) {
    if ((res?.code ?: 0) != 403) {
        return false
    }

    def bodyType = (res?.body instanceof Map) ? (res.body.type ?: '').toString() : ''
    return bodyType == 'already_stocked'
}

/*
 * CPS ミスマッチ回避のため、target 一覧の整形を NonCPS で実施する。
 * - private 記事を除外
 * - item_id で重複排除
 * - createdAt 昇順
 */
@NonCPS
def normalizeTargets(List rawTargets) {
    def result = []
    def seen = [] as Set

    for (def t : (rawTargets ?: [])) {
        if (!(t instanceof Map)) {
            continue
        }
        if (t.privateFlg == true) {
            continue
        }

        def id = (t.id ?: '').toString().trim()
        if (!id || seen.contains(id)) {
            continue
        }

        seen << id
        result << t
    }

    result.sort { a, b ->
        def aCreated = (a.createdAt ?: '').toString()
        def bCreated = (b.createdAt ?: '').toString()
        aCreated <=> bCreated
    }

    return result
}

/*
 * state ID 一覧を正規化する（空除去・trim・重複排除・上限件数適用）。
 */
@NonCPS
def normalizeStateIds(List rawIds, int maxSize) {
    def result = []
    def seen = [] as Set

    for (def idRaw : (rawIds ?: [])) {
        def id = (idRaw ?: '').toString().trim()
        if (!id || seen.contains(id)) {
            continue
        }

        seen << id
        result << id

        if (result.size() >= maxSize) {
            break
        }
    }

    return result
}

/*
 * state ファイルから処理済み item_id を読み込む。
 */
def loadStateIds(String stateFilePath) {
    def output = sh(
        script: "cat '${shellQuote(stateFilePath)}' 2>/dev/null || true",
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
 */
def discoverOrganizationItemIds(Map cfg, String orgName) {
    List<String> candidateUrls = [
        "https://qiita.com/organizations/${orgName}/activities.atom",
        "https://qiita.com/organizations/${orgName}/feed"
    ]

    String feedXml = ''
    String usedUrl = ''

    for (String url : candidateUrls) {
        def res = httpGetText(cfg, url, false)
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
def qiitaGet(Map cfg, String apiBase, String apiPath) {
    return qiitaRequest(cfg, apiBase, 'GET', apiPath, null)
}

/*
 * Qiita PUT ラッパー
 */
def qiitaPut(Map cfg, String apiBase, String apiPath) {
    return qiitaRequest(cfg, apiBase, 'PUT', apiPath, null)
}

/*
 * Qiita API リクエスト共通処理
 */
def qiitaRequest(Map cfg, String apiBase, String method, String apiPath, String requestBody) {
    String url = "${apiBase}${apiPath}"
    return httpRequestWithRetry(
        cfg,
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
def httpGetText(Map cfg, String url, boolean withAuth) {
    return httpRequestWithRetry(cfg, 'GET', url, withAuth, null, null)
}

/*
 * リトライ付き HTTP リクエスト。
 * リトライ対象: code=0 / 408 / 425 / 429 / 5xx
 */
def httpRequestWithRetry(Map cfg, String method, String url, boolean withAuth, String requestBody, String contentType) {
    int maxAttempts = (cfg?.httpRetryCount ?: 3) as Integer
    int timeoutSec  = (cfg?.httpTimeoutSec ?: 30) as Integer

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
