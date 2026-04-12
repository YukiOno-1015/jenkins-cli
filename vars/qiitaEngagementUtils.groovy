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
