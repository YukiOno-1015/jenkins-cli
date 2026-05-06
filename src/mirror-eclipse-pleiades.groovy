import groovy.json.JsonOutput
import groovy.transform.Field

/*
 * Pleiades All in One (Eclipse) の Mac/Java 版を監視し、
 * 新版が出ていれば DL して Nexus Raw リポジトリへミラーリングするパイプライン。
 *
 * 動作概要:
 * 1. 1 日 1 回 willbrains.jp をスクレイピングし、最新の Mac/Java dmg を検出する
 * 2. 前回取得した version / buildDate と比較し、新版なら下記を順次実行する
 *    - curl でローカルへダウンロード
 *    - nexusRawUpload で `<NEXUS_REMOTE_DIR_PREFIX>/<version>/` 配下へ push
 *    - state ファイルを更新
 *    - Slack へ完了通知
 *
 * 必要な Jenkins Credentials:
 *   nexus              (Username with password) : Nexus Raw アップロード用（nexusRawUpload の既定 ID）
 *   slack-bot-token    (Secret text, 任意)      : Slack chat.postMessage 用 Bot/User Token
 *                                                 SLACK_CHANNEL を空欄にすれば通知自体をスキップする
 *
 * state 永続化:
 *   PVC `eclipse-pleiades-state` を /eclipse-monitor-state へマウントする想定。
 *   PVC 名は PVC_CLAIM_NAME パラメータで上書き可能。空欄なら emptyDir で毎回再 DL。
 *
 * 設計メモ:
 * - Pleiades は API を提供していないため HTML スクレイピング依存
 *   サイト改修で壊れる可能性があるため、検出ロジックは Python に分離して差し替えやすくしている
 * - dmg は数百 MB あるため Pod の ephemeral-storage を 5Gi まで許可
 */

def libBranch = env.CHANGE_BRANCH ?: env.BRANCH_NAME ?: 'main'
def libId = "jqit-lib@${libBranch}"

try {
    library libId
} catch (err) {
    echo "${libId} の読み込みに失敗しました。jqit-lib@main へフォールバックします。"
    library 'jqit-lib@main'
}

@Field String DEFAULT_AGENT_IMAGE = 'nexus-docker-pull.sk4869.info/honoka4869/jenkins-maven-node:latest'
// state は固定パスに置く（パラメータ化しない）。
// PVC 名のみ環境差を許容するためパラメータで上書き可能。
@Field String STATE_MOUNT_PATH = '/eclipse-monitor-state'
@Field String STATE_FILE_NAME = 'pleiades-mac-java.json'
@Field String DEFAULT_PVC_CLAIM_NAME = 'eclipse-pleiades-state'
@Field String DEFAULT_PLEIADES_INDEX_URL = 'https://willbrains.jp/pleiades.html'
@Field String DEFAULT_DOWNLOAD_BASE_URL = 'https://ftp.jaist.ac.jp/pub/mergedoc/pleiades/'
@Field String DEFAULT_NEXUS_REPO = 'raw-tools'
@Field String DEFAULT_NEXUS_PREFIX = 'eclipse'

pipeline {
    agent none

    triggers {
        cron('TZ=Asia/Tokyo\nH 4 * * *')
    }

    options {
        skipDefaultCheckout(true)
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timeout(time: 90, unit: 'MINUTES')
    }

    parameters {
        string(
            name: 'PLEIADES_INDEX_URL',
            defaultValue: DEFAULT_PLEIADES_INDEX_URL,
            description: 'Pleiades 公式インデックスページ URL'
        )
        string(
            name: 'TARGET_VARIANTS',
            defaultValue: 'mac,mac-jre',
            description: 'Mac/Java の対象バリアント（カンマ区切り。mac=JRE 同梱なし、mac-jre=JRE 同梱）'
        )
        string(
            name: 'NEXUS_REPO',
            defaultValue: DEFAULT_NEXUS_REPO,
            description: 'Nexus Raw リポジトリ名（nexusRawUpload に渡す）'
        )
        string(
            name: 'NEXUS_REMOTE_DIR_PREFIX',
            defaultValue: DEFAULT_NEXUS_PREFIX,
            description: 'Nexus 上の格納ディレクトリ。最終パスは ${prefix}/${version}/${filename}'
        )
        string(
            name: 'SLACK_CHANNEL',
            defaultValue: 'C0B1HJFPTV5',
            description: 'Slack 通知先チャンネル ID（既定: Eclipse 更新通知チャンネル。空欄にすれば通知をスキップ）'
        )
        string(
            name: 'SLACK_TOKEN_CREDENTIAL_ID',
            defaultValue: 'slack-bot-token',
            description: 'Slack Bot/User Token の Jenkins Credential ID（Secret text）'
        )
        string(
            name: 'PVC_CLAIM_NAME',
            defaultValue: DEFAULT_PVC_CLAIM_NAME,
            description: "state を永続化する PVC 名。マウント先 ${STATE_MOUNT_PATH} / ファイル ${STATE_FILE_NAME} は固定。空欄なら emptyDir で毎回再 DL"
        )
        booleanParam(
            name: 'DRY_RUN',
            defaultValue: false,
            description: 'true なら DL / Nexus アップロード / state 更新 / Slack 通知を全てスキップ'
        )
        booleanParam(
            name: 'FORCE',
            defaultValue: false,
            description: 'true なら同一バージョンでも再 DL / 再アップロードする'
        )
    }

    stages {
        stage('Mirror Pleiades') {
            agent {
                kubernetes {
                    defaultContainer 'main'
                    yaml buildAgentPodYaml()
                }
            }

            steps {
                script {
                    def cfg = buildConfig()

                    echo '========================================='
                    echo 'Pleiades Eclipse ミラーリング'
                    echo '========================================='
                    echo "Index URL          : ${cfg.indexUrl}"
                    echo "Target Variants    : ${cfg.targetVariants.join(', ')}"
                    echo "Nexus Repo         : ${cfg.nexusRepo}"
                    echo "Nexus Prefix       : ${cfg.nexusPrefix}"
                    echo "State File         : ${cfg.stateFilePath}"
                    echo "Slack Channel      : ${cfg.slackChannel ?: '(未指定 → 通知スキップ)'}"
                    echo "DRY_RUN            : ${cfg.dryRun}"
                    echo "FORCE              : ${cfg.force}"
                    echo '========================================='

                    if (!cfg.pvcClaimName) {
                        echo '[WARN] PVC_CLAIM_NAME 未指定です。Pod 再作成で state が失われるため、毎回 DL が走る可能性があります。'
                    }

                    sh '''#!/bin/bash
                        set -euo pipefail
                        echo '--- 必須コマンド確認 ---'
                        command -v python3 || command -v python
                        command -v curl
                    '''

                    // 1. 最新版を検出
                    writeFile(file: '.pleiades-detect.py', text: buildDetectScript())
                    writeFile(
                        file: '.pleiades-detect-config.json',
                        text: JsonOutput.toJson([
                            indexUrl       : cfg.indexUrl,
                            downloadBaseUrl: cfg.downloadBaseUrl,
                            targetVariants : cfg.targetVariants
                        ])
                    )

                    sh '''#!/bin/bash
                        set -euo pipefail
                        PY=$(command -v python3 || command -v python)
                        "$PY" .pleiades-detect.py \
                          --config .pleiades-detect-config.json \
                          --output .pleiades-detect-result.json
                        echo '--- 検出結果 ---'
                        cat .pleiades-detect-result.json
                    '''

                    def detected = readJSON(file: '.pleiades-detect-result.json')
                    if (detected.error) {
                        error("最新版検出に失敗しました: ${detected.error}")
                    }
                    if (!detected.files || (detected.files as List).isEmpty()) {
                        error('検出結果に対象バリアントのファイルが含まれていません。TARGET_VARIANTS を確認してください。')
                    }

                    echo "検出: year=${detected.year}, version=${detected.version}, buildDate=${detected.buildDate}, files=${detected.files.size()}"
                    detected.files.each { f ->
                        echo "  - ${f.variant}: ${f.filename}${f.md5 ? " (MD5: ${f.md5})" : ''}"
                        echo "      ${f.downloadUrl}"
                    }

                    // 2. state 比較
                    def stateDir = sh(
                        script: "dirname '${shellQuote(cfg.stateFilePath)}'",
                        returnStdout: true
                    ).trim()
                    sh "mkdir -p '${shellQuote(stateDir)}'"

                    def state = loadState(cfg.stateFilePath)
                    boolean isNew = (state.version != detected.version) || (state.buildDate != detected.buildDate) || cfg.force

                    echo "前回 state : version=${state.version ?: '(未取得)'}, buildDate=${state.buildDate ?: '(未取得)'}"
                    echo "新規取り込み : ${isNew}"

                    if (!isNew) {
                        echo '同一バージョンのためスキップします（FORCE=true で強制実行可能）。'
                        currentBuild.description = "skipped: ${detected.version} (${detected.buildDate})"
                        return
                    }

                    if (cfg.dryRun) {
                        echo '[DRY_RUN] DL / Nexus アップロード / state 更新 / Slack 通知をスキップします。'
                        currentBuild.description = "dry-run: ${detected.version} (${detected.buildDate})"
                        return
                    }

                    // 3. DL → Nexus アップロード
                    String remoteDir = "${cfg.nexusPrefix}/${detected.version}"
                    List<Map> downloaded = []

                    detected.files.each { Map f ->
                        String localPath = "/tmp/${f.filename}"
                        echo "→ DL: ${f.downloadUrl}"
                        sh """#!/bin/bash
                            set -euo pipefail
                            curl -fL --retry 3 --retry-delay 10 --max-time 3600 \\
                              -o '${shellQuote(localPath)}' \\
                              '${shellQuote(f.downloadUrl)}'
                            ls -lh '${shellQuote(localPath)}'
                        """
                        if (f.md5) {
                            echo "→ MD5 検証: ${f.md5}"
                            sh """#!/bin/bash
                                set -euo pipefail
                                actual=\$(md5sum '${shellQuote(localPath)}' | awk '{print \$1}')
                                expected='${shellQuote(f.md5.toString().toLowerCase())}'
                                if [ "\$actual" != "\$expected" ]; then
                                    echo "MD5 不一致: expected=\$expected, actual=\$actual" >&2
                                    exit 1
                                fi
                                echo "MD5 一致: \$actual"
                            """
                        }
                        downloaded << [local: localPath, file: f]
                    }

                    downloaded.each { Map d ->
                        nexusRawUpload(
                            file     : d.local,
                            remoteDir: remoteDir,
                            nexusRepo: cfg.nexusRepo
                        )
                    }

                    // 4. state 更新
                    def newState = [
                        year     : detected.year,
                        version  : detected.version,
                        buildDate: detected.buildDate,
                        updatedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone('Asia/Tokyo')),
                        nexusRepo: cfg.nexusRepo,
                        nexusPath: remoteDir,
                        files    : detected.files.collect { [variant: it.variant, filename: it.filename, md5: it.md5] }
                    ]
                    writeFile(
                        file: cfg.stateFilePath,
                        text: JsonOutput.prettyPrint(JsonOutput.toJson(newState)) + '\n'
                    )
                    echo "state を更新しました: ${cfg.stateFilePath}"

                    // 5. Slack 通知（SLACK_CHANNEL 未指定ならスキップ）
                    if (cfg.slackChannel) {
                        try {
                            withCredentials([string(credentialsId: cfg.slackTokenCredentialId, variable: 'SLACK_TOKEN')]) {
                                def lines = []
                                lines << 'Eclipse Pleiades の新版を Nexus Raw へミラーリングしました'
                                lines << ''
                                lines << "Version : ${detected.version}"
                                lines << "Build   : ${detected.buildDate}"
                                lines << "Nexus   : ${cfg.nexusRepo}/${remoteDir}/"
                                lines << ''
                                lines << 'Files:'
                                detected.files.each { f ->
                                    lines << "  - ${f.variant}: ${f.filename}"
                                }
                                if (env.BUILD_URL) {
                                    lines << ''
                                    lines << "Jenkins : ${env.BUILD_URL}"
                                }

                                postSlackMessage(
                                    JsonOutput.toJson([
                                        channel     : cfg.slackChannel,
                                        text        : lines.join('\n'),
                                        mrkdwn      : true,
                                        unfurl_links: false,
                                        unfurl_media: false
                                    ]),
                                    'SLACK_TOKEN'
                                )
                            }
                        } catch (Exception ex) {
                            // Credential 未登録などで通知に失敗しても、ミラーリング本体は成功扱いを維持する
                            echo "[WARN] Slack 通知に失敗しました（処理は続行）: ${ex.message}"
                        }
                    } else {
                        echo 'SLACK_CHANNEL 未指定のため Slack 通知をスキップします。'
                    }

                    currentBuild.description = "uploaded: ${detected.version} (${detected.buildDate})"

                    // 6. ローカル一時ファイル削除（ephemeral-storage を逼迫させないため）
                    sh '''
                        rm -f /tmp/pleiades-*.dmg 2>/dev/null || true
                    '''

                    echo '✓ ミラーリング完了'
                }
            }

            post {
                always {
                    sh '''
                        rm -f .pleiades-detect.py .pleiades-detect-config.json .pleiades-detect-result.json 2>/dev/null || true
                    '''
                }
            }
        }
    }

    post {
        success {
            echo 'Pleiades ミラーリングパイプラインが正常終了しました。'
        }
        failure {
            echo 'Pleiades ミラーリングパイプラインが失敗しました。コンソールログ・state・Nexus 設定を確認してください。'
        }
    }
}

/* ============================================================
 * 設定ヘルパー
 * ============================================================
 */

def buildConfig() {
    String slackChannel = (params.SLACK_CHANNEL ?: '').trim()

    List<String> targetVariants = ((params.TARGET_VARIANTS ?: 'mac,mac-jre') as String)
        .split(',')
        .collect { it.trim() }
        .findAll { it }

    return [
        indexUrl              : ((params.PLEIADES_INDEX_URL ?: DEFAULT_PLEIADES_INDEX_URL) as String).trim(),
        downloadBaseUrl       : DEFAULT_DOWNLOAD_BASE_URL,
        targetVariants        : targetVariants,
        nexusRepo             : ((params.NEXUS_REPO ?: DEFAULT_NEXUS_REPO) as String).trim(),
        nexusPrefix           : (((params.NEXUS_REMOTE_DIR_PREFIX ?: DEFAULT_NEXUS_PREFIX) as String).trim()).replaceAll('/+$', ''),
        slackChannel          : slackChannel,
        slackTokenCredentialId: ((params.SLACK_TOKEN_CREDENTIAL_ID ?: 'slack-bot-token') as String).trim(),
        pvcClaimName          : (params.PVC_CLAIM_NAME ?: '').trim(),
        stateMountPath        : STATE_MOUNT_PATH,
        stateFilePath         : "${STATE_MOUNT_PATH}/${STATE_FILE_NAME}",
        dryRun                : params.DRY_RUN == true,
        force                 : params.FORCE == true
    ]
}

def buildAgentPodYaml() {
    String pvcClaimName = (params.PVC_CLAIM_NAME ?: '').trim()

    String volumeSource = pvcClaimName
        ? """persistentVolumeClaim:
        claimName: "${pvcClaimName}" """
        : 'emptyDir: {}'

    return """---
apiVersion: v1
kind: Pod
spec:
  restartPolicy: Never
  imagePullSecrets:
    - name: nexus
  containers:
    - name: main
      image: "${DEFAULT_AGENT_IMAGE}"
      command:
        - cat
      tty: true
      resources:
        requests:
          cpu: "250m"
          memory: "512Mi"
          ephemeral-storage: "3Gi"
        limits:
          cpu: "1"
          memory: "1Gi"
          ephemeral-storage: "5Gi"
      volumeMounts:
        - name: state
          mountPath: "${STATE_MOUNT_PATH}"
  volumes:
    - name: state
      ${volumeSource}
"""
}

def loadState(String stateFilePath) {
    String output = sh(
        script: "cat '${shellQuote(stateFilePath)}' 2>/dev/null || true",
        returnStdout: true
    ).trim()

    if (!output) {
        return [version: null, buildDate: null]
    }

    try {
        return readJSON(text: output)
    } catch (Exception ignored) {
        return [version: null, buildDate: null]
    }
}

def shellQuote(String value) {
    return (value ?: '').replace("'", "'\"'\"'")
}

def postSlackMessage(String payloadText, String credentialEnvVar) {
    writeFile(file: '.pleiades-slack-payload.json', text: payloadText)

    sh """#!/bin/bash
        set -euo pipefail
        export SLACK_API_PAYLOAD_FILE='.pleiades-slack-payload.json'
        export SLACK_TOKEN="\${${credentialEnvVar}}"
        PY=\$(command -v python3 || command -v python)
        "\$PY" - <<'PY'
import json
import os
import urllib.request

payload_file = os.environ['SLACK_API_PAYLOAD_FILE']
slack_token = os.environ['SLACK_TOKEN']

with open(payload_file, 'rb') as fh:
    payload = fh.read()

request = urllib.request.Request(
    'https://slack.com/api/chat.postMessage',
    data=payload,
    headers={
        'Content-Type': 'application/json; charset=utf-8',
        'Authorization': f'Bearer {slack_token}',
    },
    method='POST'
)

with urllib.request.urlopen(request, timeout=20) as response:
    body = response.read().decode('utf-8')

slack_response = json.loads(body)
if not slack_response.get('ok'):
    raise SystemExit(f"Slack API error: {slack_response.get('error', 'unknown_error')}")
PY
    """

    sh 'rm -f .pleiades-slack-payload.json 2>/dev/null || true'
}

/* ============================================================
 * 検出スクリプト (Python)
 * ============================================================
 *
 * 動作:
 * 1. PLEIADES_INDEX_URL (既定 willbrains.jp/pleiades.html) をフェッチ
 * 2. 中の `pleiades_distros<YYYY>.html` リンクから最新年を選択
 * 3. その distros ページから `pleiades-redirect/<year>/pleiades_java-mac(_jre)?\.zip\.html?v=<YYYYMMDD>` を抽出
 * 4. 各 redirect ページを取得し、<title> から実 dmg ファイル名と MD5 を抽出
 * 5. 実ダウンロード URL は `<downloadBaseUrl><year>/<filename>` で組み立てる
 *
 * 出力 (--output 先 JSON) 例:
 *   {
 *     "year": 2026,
 *     "version": "2026-03",
 *     "buildDate": "20260322",
 *     "files": [
 *       {"variant": "mac",     "filename": "...", "downloadUrl": "...", "md5": "..."},
 *       {"variant": "mac-jre", "filename": "...", "downloadUrl": "...", "md5": "..."}
 *     ]
 *   }
 * 失敗時:
 *   {"error": "..."}
 */
String buildDetectScript() {
    return '''#!/usr/bin/env python3
import argparse
import json
import re
import urllib.request
from urllib.parse import urljoin


USER_AGENT = "jenkins-pleiades-monitor/1.0"


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read().decode("utf-8", errors="replace")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    with open(args.config, "r", encoding="utf-8") as fh:
        cfg = json.load(fh)

    index_url = cfg["indexUrl"]
    download_base = cfg.get("downloadBaseUrl", "https://ftp.jaist.ac.jp/pub/mergedoc/pleiades/")
    if not download_base.endswith("/"):
        download_base += "/"
    target_variants = [v.strip().lower() for v in cfg.get("targetVariants", []) if v.strip()]

    result = {}
    try:
        # 1. インデックスページから distros<YYYY>.html を集める
        index_html = fetch(index_url)
        year_pattern = re.compile(r\'href="(pleiades_distros(\\d{4})\\.html)"\')
        year_pairs = []
        for m in year_pattern.finditer(index_html):
            try:
                year_pairs.append((int(m.group(2)), m.group(1)))
            except ValueError:
                continue

        if not year_pairs:
            result = {"error": "no pleiades_distros<YYYY>.html links found in index"}
            with open(args.output, "w", encoding="utf-8") as fh:
                json.dump(result, fh, ensure_ascii=False, indent=2)
            return

        latest_year, distros_href = max(year_pairs)
        distros_url = urljoin(index_url, distros_href)

        # 2. 最新年の distros ページから Mac/Java の redirect URL を抽出
        # URL では `pleiades_java-mac_jre.zip.html` (アンダースコア) だが
        # 実 dmg ファイル名は `pleiades-...-java-mac-jre_...dmg` (ハイフン) と表記が異なる。
        # ここでは URL 内の表記 (mac / mac_jre) を捕捉し、後で 'mac-jre' へ正規化する。
        distros_html = fetch(distros_url)
        redirect_pattern = re.compile(
            r\'href="(pleiades-redirect/\' + str(latest_year)
            + r\'/pleiades_java-(mac(?:_jre)?)\\.zip\\.html\\?v=(\\d{8}))"\',
            re.IGNORECASE,
        )

        seen = set()
        files = []
        for m in redirect_pattern.finditer(distros_html):
            href, raw_variant, build_date = m.group(1), m.group(2).lower(), m.group(3)
            # URL 内の `mac_jre` → `mac-jre` へ正規化（ユーザー指定の表記と揃える）
            variant = raw_variant.replace("_", "-")
            key = (variant, build_date)
            if key in seen:
                continue
            seen.add(key)
            if target_variants and variant not in target_variants:
                continue

            redirect_url = urljoin(distros_url, href)
            try:
                redirect_html = fetch(redirect_url)
            except Exception as e:
                files.append({
                    "variant": variant,
                    "error": f"redirect fetch failed: {e}",
                })
                continue

            # <title>ダウンロード pleiades-2026-03-java-mac-jre_20260322.dmg</title>
            title_match = re.search(r\'<title>[^<]*?(pleiades-[^\\s<]+\\.dmg)\', redirect_html)
            if not title_match:
                continue
            filename = title_match.group(1)

            fn_match = re.match(
                r\'pleiades-(\\d{4}-\\d{2})(?:-r\\d+)?-java-mac(?:-jre)?_\\d{8}\\.dmg\',
                filename,
            )
            if not fn_match:
                continue
            version = fn_match.group(1)

            md5_match = re.search(r\'MD5:\\s*([0-9a-fA-F]{32})\', redirect_html)
            md5 = md5_match.group(1).lower() if md5_match else None

            download_url = download_base + str(latest_year) + "/" + filename

            files.append({
                "variant": variant,
                "filename": filename,
                "downloadUrl": download_url,
                "md5": md5,
                "version": version,
                "buildDate": build_date,
            })

        if not files:
            result = {
                "error": (
                    f"no Mac/Java dmg redirect found on {distros_url} "
                    f"(targetVariants={target_variants})"
                )
            }
        else:
            valid = [f for f in files if "filename" in f]
            if not valid:
                result = {"error": "all candidates failed to resolve filename"}
            else:
                # 全変種で同一 (version, buildDate) のものを最新として採用する
                valid.sort(key=lambda f: (f["version"], f["buildDate"]))
                latest = valid[-1]
                target_files = [
                    {
                        "variant": f["variant"],
                        "filename": f["filename"],
                        "downloadUrl": f["downloadUrl"],
                        "md5": f["md5"],
                    }
                    for f in valid
                    if f["version"] == latest["version"]
                    and f["buildDate"] == latest["buildDate"]
                ]
                result = {
                    "year": latest_year,
                    "version": latest["version"],
                    "buildDate": latest["buildDate"],
                    "files": target_files,
                }

    except Exception as e:
        result = {"error": f"{type(e).__name__}: {e}"}

    with open(args.output, "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
'''
}
