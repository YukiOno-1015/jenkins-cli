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
    def raw = sh(returnStdout: true, script: '''
        set +e
        if command -v speedtest-cli >/dev/null 2>&1; then
            out=$(speedtest-cli --secure --json 2>/dev/null)
            rc=$?
            if [ $rc -eq 0 ] && [ -n "$out" ]; then
                printf '%s' "$out"
                exit 0
            fi
        fi
        # フォールバック: curl ダウンロードで bytes/sec を計測
        url="''' + (fallbackUrl ?: '') + '''"
        if [ -z "$url" ]; then
            printf '{"error":"speedtest-cli unavailable and EXTERNAL_FALLBACK_URL is empty"}'
            exit 0
        fi
        # -w で帯域 (B/s) と所要時間を取得
        metrics=$(curl -L -s -o /dev/null -w '{"speed_download_bps":%{speed_download},"size_download_bytes":%{size_download},"time_total_sec":%{time_total},"http_code":%{http_code}}' "$url" 2>/dev/null)
        rc=$?
        if [ $rc -ne 0 ] || [ -z "$metrics" ]; then
            printf '{"error":"curl fallback failed","url":"%s","exit_code":%d}' "$url" "$rc"
            exit 0
        fi
        printf '{"method":"curl_fallback","url":"%s","metrics":%s}' "$url" "$metrics"
    ''').trim()
    return parseJsonSafely(raw, 'external')
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
 * シェルから取得した文字列を JSON として安全に解析する。
 * 解析失敗時は raw 文字列とエラー内容を保持したマップで返す。
 */
def parseJsonSafely(String raw, String label) {
    if (!raw) {
        return [error: "${label}: empty output".toString()]
    }
    try {
        return new groovy.json.JsonSlurperClassic().parseText(raw)
    } catch (Exception e) {
        echo "${label}: JSON 解析に失敗しました (${e.message})"
        return [error: "${label}: invalid JSON".toString(), raw: raw]
    }
}
