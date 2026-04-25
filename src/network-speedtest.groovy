/*
 * Kubernetes コンテナ Agent 上で外部向けネットワーク帯域を定期計測する Jenkins パイプラインです。
 *
 * 計測対象:
 *   - 外部 (インターネット) 向けスピードテスト
 *     speedtest-cli が利用可能ならそれを優先し、利用できない場合は curl でのダウンロード時間から
 *     bytes/sec を換算する代替計測を実施する。
 *
 * 設計方針:
 * - Jenkins の cron で定期実行し、結果は 1 つの JSON にまとめて echo + archiveArtifacts する
 * - 計測ツールは nicolaka/netshoot に同梱のものを優先利用し、不足時のみランタイムで補完する
 * - VIP / Tunnel 等の内部経路は Pod から到達できない可能性が高いため対象外とする
 *   (必要になった場合は INTERNAL_* 系パラメータと iperf3 ステップを後追いで追加する想定)
 */

@groovy.transform.Field String DEFAULT_AGENT_IMAGE = 'nicolaka/netshoot:latest'

pipeline {
    agent none

    triggers {
        // 既定は 6 時間おきに実行する。負荷を懸念する場合はパラメータ調整より cron を直接調整する。
        cron('TZ=Asia/Tokyo\nH H/6 * * *')
    }

    options {
        skipDefaultCheckout(true)
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '50'))
        timeout(time: 20, unit: 'MINUTES')
    }

    parameters {
        string(
            name: 'AGENT_IMAGE',
            defaultValue: 'nicolaka/netshoot:latest',
            description: 'Pod で利用するコンテナイメージ。curl / speedtest-cli が動くものを指定する'
        )
        string(
            name: 'NAMESPACE',
            defaultValue: 'jenkins',
            description: 'Pod を起動する Kubernetes namespace'
        )
        string(
            name: 'EXTERNAL_FALLBACK_URL',
            defaultValue: 'https://speed.cloudflare.com/__down?bytes=104857600',
            description: 'speedtest-cli が利用できない場合に curl でダウンロードする URL (既定は Cloudflare の 100MiB)'
        )
        string(
            name: 'RESULT_FILE_NAME',
            defaultValue: 'network-speedtest-result.json',
            description: '結果 JSON のファイル名 (archiveArtifacts 対象)'
        )
    }

    stages {
        stage('外部スピードテスト実行') {
            agent {
                kubernetes {
                    namespace params.NAMESPACE
                    defaultContainer 'netshoot'
                    yaml buildPodYaml(params.AGENT_IMAGE)
                }
            }
            steps {
                script {
                    // 計測ツールの可用性チェックと不足分のランタイム導入
                    ensureTools()

                    def results = [
                        startedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone('Asia/Tokyo')),
                        jenkins  : [
                            buildNumber: env.BUILD_NUMBER,
                            jobName    : env.JOB_NAME,
                            buildUrl   : env.BUILD_URL
                        ],
                        external : null
                    ]

                    results.publicIp = fetchPublicIp()
                    results.external = runExternalTest(params.EXTERNAL_FALLBACK_URL?.toString()?.trim())
                    results.finishedAt = new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone('Asia/Tokyo'))

                    def jsonText = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(results))
                    def fileName = (params.RESULT_FILE_NAME ?: 'network-speedtest-result.json').toString().trim()

                    // Jenkins コンソールで一目で分かるよう、人間可読のサマリーを先に表示する
                    printSummary(results)

                    // ジョブのトップ画面 (ビルド一覧 / ビルド詳細ページ / Stage View 近辺) で
                    // 一目で分かるよう、currentBuild.description にサマリーをセットする。
                    // HTML を許容するよう、Manage Jenkins > Configure Global Security の
                    // 「Markup Formatter」を Safe HTML 以上に設定している前提。
                    // 未設定時はタグがそのまま表示されるが主情報は読める。
                    currentBuild.description = buildBuildDescriptionHtml(results)

                    echo '===== 外部ネットワークスピードテスト結果 (JSON) ====='
                    echo jsonText
                    echo '====================================================='

                    writeFile file: fileName, text: jsonText
                    archiveArtifacts artifacts: fileName, fingerprint: true, onlyIfSuccessful: false
                }
            }
        }
    }
}

/**
 * Pod テンプレート YAML を生成する。
 * netshoot イメージは sleep を持たないため `tail -f /dev/null` で常駐させる。
 */
def buildPodYaml(String image) {
    return """---
apiVersion: v1
kind: Pod
metadata:
  labels:
    jenkins: jenkins-network-speedtest
spec:
  restartPolicy: Never
  containers:
    - name: netshoot
      image: ${image}
      command: ['/bin/sh']
      args: ['-c', 'tail -f /dev/null']
      tty: true
      resources:
        requests:
          cpu: '200m'
          memory: '256Mi'
        limits:
          cpu: '1'
          memory: '1Gi'
"""
}

/**
 * 計測コマンドの可用性を確認し、不足分はランタイムで導入する。
 * netshoot は Alpine ベースのため apk を利用する。
 */
def ensureTools() {
    sh '''
        set -eu
        echo "--- 利用可能ツールの確認 ---"
        command -v curl          || true
        command -v speedtest-cli || true

        # 不足分の導入 (apk が利用可能な netshoot 前提)
        if ! command -v curl >/dev/null 2>&1 && command -v apk >/dev/null 2>&1; then
            echo "curl を apk で導入"
            apk add --no-cache curl >/dev/null
        fi

        # speedtest-cli (Python 版) は無ければ pip で追加 (任意)
        if ! command -v speedtest-cli >/dev/null 2>&1; then
            if command -v pip3 >/dev/null 2>&1; then
                echo "speedtest-cli を pip で導入"
                pip3 install --quiet --no-cache-dir speedtest-cli || echo "speedtest-cli の導入に失敗しました (curl 代替へフォールバック予定)"
            elif command -v apk >/dev/null 2>&1; then
                apk add --no-cache py3-pip >/dev/null 2>&1 || true
                pip3 install --quiet --no-cache-dir speedtest-cli >/dev/null 2>&1 || echo "speedtest-cli の導入に失敗しました (curl 代替へフォールバック予定)"
            fi
        fi
    '''
}

/**
 * 外部向けスピードテストを実施する。
 * speedtest-cli が使える場合は --json をそのまま採用し、失敗時は curl ダウンロード時間で換算する。
 */
def runExternalTest(String fallbackUrl) {
    echo '--- 外部向けスピードテスト ---'
    // curl の -w 書式はロケール依存で小数点が ',' になり JSON が壊れることがあるため
    // LC_ALL=C を強制する。speedtest-cli 側も念のため LC_ALL=C で実行する。
    // また speedtest-cli は環境によって stdout に警告等を混ぜることがあるため、
    // 出力から最初の '{' 以降のみを取り出して JSON 候補とする。
    def raw = sh(returnStdout: true, script: '''
        set +e
        export LC_ALL=C
        export LANG=C
        if command -v speedtest-cli >/dev/null 2>&1; then
            out=$(speedtest-cli --secure --json 2>&1)
            rc=$?
            if [ $rc -eq 0 ] && [ -n "$out" ]; then
                # 最初の '{' 以降を抜き出す (前置警告などを除去)
                json=$(printf '%s' "$out" | sed -n '/{/,$p')
                if [ -n "$json" ] && [ "${json#\\{}" != "$json" ]; then
                    printf '%s' "$json"
                    exit 0
                fi
                # JSON として取り出せなかった場合はマーカー付きで raw を返し、curl にフォールバック
                printf '__SPEEDTEST_NONJSON__\n%s' "$out"
            fi
        fi
        # フォールバック: curl ダウンロードで bytes/sec を計測
        url="''' + (fallbackUrl ?: '') + '''"
        if [ -z "$url" ]; then
            printf '__CURL_FALLBACK_FAILED__\n\n0\nEXTERNAL_FALLBACK_URL is empty'
            exit 0
        fi
        # 数値を改行区切りで取得し、JSON 組み立ては Groovy 側で行う
        # (curl の -w でカンマ小数や指数表記が混じっても安全に扱うため)
        raw=$(curl -L -s -o /dev/null -w '%{speed_download}\n%{size_download}\n%{time_total}\n%{http_code}\n' "$url" 2>/dev/null)
        rc=$?
        if [ $rc -ne 0 ] || [ -z "$raw" ]; then
            printf '__CURL_FALLBACK_FAILED__\n%s\n%d\n' "$url" "$rc"
            exit 0
        fi
        printf '__CURL_FALLBACK__\n%s\n%s' "$url" "$raw"
    ''').trim()

    // 何が返ってきたかを必ず可視化する (長すぎる場合は切り詰め)
    def head = raw == null ? '' : raw.toString()
    if (head.length() > 800) {
        head = head.substring(0, 800) + '... (truncated)'
    }
    echo "external: シェル戻り値 (先頭800文字): ${head}"

    // curl フォールバック専用フォーマットを優先処理 (JSON 解析より先)
    if (raw.startsWith('__CURL_FALLBACK__')) {
        return parseCurlFallbackOutput(raw)
    }
    if (raw.startsWith('__CURL_FALLBACK_FAILED__')) {
        def lines = raw.readLines()
        return [
            error    : 'curl fallback failed',
            url      : lines.size() > 1 ? lines[1] : null,
            exit_code: lines.size() > 2 ? lines[2] : null,
            detail   : lines.size() > 3 ? lines[3] : null
        ]
    }
    if (raw.startsWith('__SPEEDTEST_NONJSON__')) {
        // speedtest-cli が rc==0 だが JSON ではなかったケース。raw を保持してエラー扱いにする。
        return [error: 'speedtest-cli returned non-JSON output', raw: raw.substring('__SPEEDTEST_NONJSON__'.length()).trim()]
    }
    return parseJsonSafely(raw, 'external')
}

/**
 * curl フォールバックのシェル出力を構造化する。
 * 1 行目: マーカー、2 行目: URL、3 行目以降: speed_download / size_download / time_total / http_code。
 * 数値を Groovy 側でパースし、最終的に既存の {method, url, metrics: {...}} 形式へ整える。
 */
def parseCurlFallbackOutput(String raw) {
    def lines = raw.readLines()
    def url = lines.size() > 1 ? lines[1] : null
    def speedBps = lines.size() > 2 ? toNumberOrNull(lines[2]) : null
    def sizeBytes = lines.size() > 3 ? toNumberOrNull(lines[3]) : null
    def timeSec = lines.size() > 4 ? toNumberOrNull(lines[4]) : null
    def httpCode = lines.size() > 5 ? toNumberOrNull(lines[5]) : null
    return [
        method : 'curl_fallback',
        url    : url,
        metrics: [
            speed_download_bps : speedBps,
            size_download_bytes: sizeBytes,
            time_total_sec     : timeSec,
            http_code          : httpCode
        ]
    ]
}

/**
 * curl 出力の数値を寛容にパースする。
 * カンマ小数 / 指数表記 / 余計な空白を吸収し、解析できなければ null を返す。
 */
def toNumberOrNull(String s) {
    if (s == null) {
        return null
    }
    def t = s.trim().replace(',', '.')
    if (!t) {
        return null
    }
    try {
        return new BigDecimal(t)
    } catch (Exception ignore) {
        return null
    }
}

/**
 * 計測元 Pod から見たグローバル IP を取得する。
 * 取得に失敗した場合は null を返し、サマリー表示側で「不明」と扱う。
 * speedtest-cli 利用時も client.ip が含まれるが、curl フォールバック時の手掛かりとして独立に取得しておく。
 */
def fetchPublicIp() {
    def ip = sh(returnStdout: true, script: '''
        set +e
        # 複数の取得元をフォールバックしながら試す (どれかが応答すれば良い)
        for url in https://api.ipify.org https://ifconfig.me/ip https://ipv4.icanhazip.com; do
            v=$(curl -fsSL --max-time 5 "$url" 2>/dev/null | tr -d " \t\r\n")
            if [ -n "$v" ]; then
                printf '%s' "$v"
                exit 0
            fi
        done
        printf ''
    ''').trim()
    return ip ?: null
}

/**
 * 人間向けに up/down / サーバ / 自分の IP を整形して echo する。
 * speedtest-cli 形式と curl フォールバック形式の双方に対応する。
 */
def printSummary(Map results) {
    def ext = results?.external instanceof Map ? results.external : [:]
    def downMbps = null
    def upMbps = null
    def serverLabel = null
    def clientIp = results?.publicIp

    if (ext?.download != null || ext?.upload != null) {
        // speedtest-cli --json は bits/s で download / upload を返す
        if (ext.download != null) {
            downMbps = (ext.download as double) / 1_000_000.0d
        }
        if (ext.upload != null) {
            upMbps = (ext.upload as double) / 1_000_000.0d
        }
        if (ext.server instanceof Map) {
            def s = ext.server
            serverLabel = [s.sponsor, s.name, s.country, s.host].findAll { it }.join(' / ')
        }
        if (!clientIp && ext.client instanceof Map) {
            clientIp = ext.client.ip
        }
    } else if (ext?.metrics instanceof Map) {
        // curl フォールバック: speed_download_bps はバイト/秒
        def bps = ext.metrics.speed_download_bps
        if (bps != null) {
            downMbps = ((bps as double) * 8.0d) / 1_000_000.0d
        }
        serverLabel = (ext.url ?: '(curl fallback)').toString()
    }

    def fmt = { Double v -> v == null ? '不明' : String.format('%.2f Mbps', v) }
    echo '===== 外部ネットワークスピードテスト サマリー ====='
    echo "  ダウンロード : ${fmt(downMbps)}"
    echo "  アップロード : ${fmt(upMbps)}"
    echo "  サーバ       : ${serverLabel ?: '不明'}"
    echo "  自分の IP    : ${clientIp ?: '不明'}"
    if (ext?.error) {
        echo "  ※ エラー   : ${ext.error}"
    }
    echo '=================================================='
}

/**
 * ジョブトップ画面 / ビルド一覧に表示されるサマリー文字列 (HTML) を生成する。
 * currentBuild.description にセットされ、Stage View 近辺 (ビルド詳細) で表示される。
 */
def buildBuildDescriptionHtml(Map results) {
    def metrics = extractDisplayMetrics(results)
    def fmt = { Double v -> v == null ? '不明' : String.format('%.2f Mbps', v) }
    def esc = { Object s ->
        if (s == null) {
            return '不明'
        }
        return s.toString()
            .replace('&', '&amp;')
            .replace('<', '&lt;')
            .replace('>', '&gt;')
            .replace('"', '&quot;')
    }
    def parts = []
    parts << "⬇ ダウン: ${esc(fmt(metrics.downMbps))}".toString()
    parts << "⬆ アップ: ${esc(fmt(metrics.upMbps))}".toString()
    parts << "サーバ: ${esc(metrics.serverLabel)}".toString()
    parts << "IP: ${esc(metrics.clientIp)}".toString()
    if (metrics.error) {
        parts << "⚠ ${esc(metrics.error)}".toString()
    }
    return parts.join(' | ')
}

/**
 * 表示用の指標を一箇所で抽出する。
 * speedtest-cli 形式と curl フォールバック形式の双方に対応し、
 * printSummary / buildBuildDescriptionHtml の両方から利用される。
 */
def extractDisplayMetrics(Map results) {
    def ext = results?.external instanceof Map ? results.external : [:]
    Double downMbps = null
    Double upMbps = null
    String serverLabel = null
    String clientIp = results?.publicIp
    String error = ext?.error

    if (ext?.download != null || ext?.upload != null) {
        if (ext.download != null) {
            downMbps = ((ext.download as double) / 1_000_000.0d) as Double
        }
        if (ext.upload != null) {
            upMbps = ((ext.upload as double) / 1_000_000.0d) as Double
        }
        if (ext.server instanceof Map) {
            def s = ext.server
            serverLabel = [s.sponsor, s.name, s.country, s.host].findAll { it }.join(' / ') ?: null
        }
        if (!clientIp && ext.client instanceof Map) {
            clientIp = ext.client.ip
        }
    } else if (ext?.metrics instanceof Map) {
        def bps = ext.metrics.speed_download_bps
        if (bps != null) {
            downMbps = (((bps as double) * 8.0d) / 1_000_000.0d) as Double
        }
        serverLabel = (ext.url ?: '(curl fallback)').toString()
    }
    return [
        downMbps   : downMbps,
        upMbps     : upMbps,
        serverLabel: serverLabel,
        clientIp   : clientIp,
        error      : error
    ]
}

/**
 * シェルから取得した文字列を JSON として安全に解析する。
 * Jenkins サンドボックスで JsonSlurperClassic#parseText が拒否されるため、
 * 標準で許可されている Pipeline Utility Steps の readJSON を利用する。
 * 解析失敗時は raw 文字列とエラー内容を保持したマップで返す。
 */
def parseJsonSafely(String raw, String label) {
    if (!raw) {
        return [error: "${label}: empty output".toString()]
    }
    try {
        return readJSON(text: raw)
    } catch (Exception e) {
        echo "${label}: JSON 解析に失敗しました (${e.message})"
        return [error: "${label}: invalid JSON".toString(), raw: raw]
    }
}
