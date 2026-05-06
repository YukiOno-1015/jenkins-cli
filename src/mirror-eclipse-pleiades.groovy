import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic
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
 * 推奨:
 *   PVC_CLAIM_NAME      : state を Pod 再作成後も維持したい場合に PVC 名を指定する
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
@Field String DEFAULT_STATE_MOUNT_PATH = '/eclipse-monitor-state'
@Field String DEFAULT_STATE_FILE_NAME = 'pleiades-mac-java.json'
@Field String DEFAULT_PLEIADES_INDEX_URL = 'https://willbrains.jp/'
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
            name: 'TARGET_ARCHES',
            defaultValue: 'aarch64,64bit',
            description: 'Mac の対象アーキテクチャ（カンマ区切り。aarch64=Apple Silicon, 64bit=Intel）'
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
            defaultValue: '',
            description: 'Slack 通知先チャンネル名 / ID（空欄なら Slack 通知をスキップ）'
        )
        string(
            name: 'SLACK_TOKEN_CREDENTIAL_ID',
            defaultValue: 'slack-bot-token',
            description: 'Slack Bot/User Token の Jenkins Credential ID（Secret text）'
        )
        string(
            name: 'PVC_CLAIM_NAME',
            defaultValue: '',
            description: 'state を永続化する PVC 名。空欄時は emptyDir のため Pod 再作成で state が失われ再 DL が走る'
        )
        string(
            name: 'STATE_VOLUME_MOUNT_PATH',
            defaultValue: DEFAULT_STATE_MOUNT_PATH,
            description: 'state 用ボリュームのマウント先'
        )
        string(
            name: 'STATE_FILE_PATH',
            defaultValue: '',
            description: 'state JSON のパス。空欄時は ${STATE_VOLUME_MOUNT_PATH}/${DEFAULT_STATE_FILE_NAME}'
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
                    echo "Target Arches      : ${cfg.targetArches.join(', ')}"
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

                    sh '''
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
                            indexUrl    : cfg.indexUrl,
                            targetArches: cfg.targetArches
                        ])
                    )

                    sh '''
                        set -euo pipefail
                        PY=$(command -v python3 || command -v python)
                        "$PY" .pleiades-detect.py \
                          --config .pleiades-detect-config.json \
                          --output .pleiades-detect-result.json
                        echo '--- 検出結果 ---'
                        cat .pleiades-detect-result.json
                    '''

                    def detected = new JsonSlurperClassic().parseText(readFile('.pleiades-detect-result.json'))
                    if (detected.error) {
                        error("最新版検出に失敗しました: ${detected.error}")
                    }
                    if (!detected.files || (detected.files as List).isEmpty()) {
                        error('検出結果に対象アーキテクチャのファイルが含まれていません。TARGET_ARCHES を確認してください。')
                    }

                    echo "検出: version=${detected.version}, buildDate=${detected.buildDate}, files=${detected.files.size()}"
                    detected.files.each { f ->
                        echo "  - ${f.arch}: ${f.filename}"
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
                        sh """
                            set -euo pipefail
                            curl -fL --retry 3 --retry-delay 10 --max-time 3600 \\
                              -o '${shellQuote(localPath)}' \\
                              '${shellQuote(f.downloadUrl)}'
                            ls -lh '${shellQuote(localPath)}'
                        """
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
                        version  : detected.version,
                        buildDate: detected.buildDate,
                        updatedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone('Asia/Tokyo')),
                        nexusRepo: cfg.nexusRepo,
                        nexusPath: remoteDir,
                        files    : detected.files.collect { [arch: it.arch, filename: it.filename] }
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
                                    lines << "  - ${f.arch}: ${f.filename}"
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

    String stateMountPath = (params.STATE_VOLUME_MOUNT_PATH ?: DEFAULT_STATE_MOUNT_PATH).trim() ?: DEFAULT_STATE_MOUNT_PATH
    String stateFilePath = (params.STATE_FILE_PATH ?: '').trim()
    if (!stateFilePath) {
        stateFilePath = "${stateMountPath}/${DEFAULT_STATE_FILE_NAME}"
    }

    List<String> targetArches = ((params.TARGET_ARCHES ?: 'aarch64,64bit') as String)
        .split(',')
        .collect { it.trim() }
        .findAll { it }

    return [
        indexUrl              : ((params.PLEIADES_INDEX_URL ?: DEFAULT_PLEIADES_INDEX_URL) as String).trim(),
        targetArches          : targetArches,
        nexusRepo             : ((params.NEXUS_REPO ?: DEFAULT_NEXUS_REPO) as String).trim(),
        nexusPrefix           : (((params.NEXUS_REMOTE_DIR_PREFIX ?: DEFAULT_NEXUS_PREFIX) as String).trim()).replaceAll('/+$', ''),
        slackChannel          : slackChannel,
        slackTokenCredentialId: ((params.SLACK_TOKEN_CREDENTIAL_ID ?: 'slack-bot-token') as String).trim(),
        pvcClaimName          : (params.PVC_CLAIM_NAME ?: '').trim(),
        stateMountPath        : stateMountPath,
        stateFilePath         : stateFilePath,
        dryRun                : params.DRY_RUN == true,
        force                 : params.FORCE == true
    ]
}

def buildAgentPodYaml() {
    String mountPath = ((params.STATE_VOLUME_MOUNT_PATH ?: DEFAULT_STATE_MOUNT_PATH) as String).trim() ?: DEFAULT_STATE_MOUNT_PATH
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
          mountPath: "${mountPath}"
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
        return new JsonSlurperClassic().parseText(output)
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
 * 役割:
 * - 与えられたインデックス URL を起点に、Pleiades の Mac/Java dmg リンクを集める
 * - サブページ (.html リンク) を 1 階層だけ追跡し、最大 30 件まで巡回する
 * - (version, buildDate) の最大値を最新版として返す
 *
 * 出力 (--output 先 JSON):
 *   {"version": "2024-12", "buildDate": "20241226",
 *    "files": [{"arch": "aarch64", "filename": "...", "downloadUrl": "..."}, ...]}
 * 失敗時:
 *   {"error": "..."}
 */
String buildDetectScript() {
    return '''#!/usr/bin/env python3
import argparse
import json
import re
import sys
import urllib.request
from urllib.parse import urljoin


USER_AGENT = "jenkins-pleiades-monitor/1.0"
SUB_PAGE_LIMIT = 30


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read().decode("utf-8", errors="replace")


def collect_dmg(html, base_url, candidates):
    pattern = re.compile(
        r\'href="([^"]*pleiades-(\\d{4}-\\d{2})(?:-r\\d+)?-java-mac-(aarch64|64bit)_(\\d{8})\\.dmg)"\',
        re.IGNORECASE,
    )
    for m in pattern.finditer(html):
        href = m.group(1)
        version = m.group(2)
        arch = m.group(3).lower()
        build_date = m.group(4)
        abs_url = urljoin(base_url, href)
        key = (version, build_date)
        candidates.setdefault(key, {})[arch] = {
            "arch": arch,
            "filename": abs_url.rsplit("/", 1)[-1],
            "downloadUrl": abs_url,
        }


def collect_sub_links(html):
    sub_pattern = re.compile(r\'href="([^"]+\\.html)"\', re.IGNORECASE)
    return list(set(sub_pattern.findall(html)))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    with open(args.config, "r", encoding="utf-8") as fh:
        cfg = json.load(fh)

    index_url = cfg["indexUrl"]
    target_arches = [a.strip().lower() for a in cfg.get("targetArches", []) if a.strip()]

    result = {}
    try:
        candidates = {}
        index_html = fetch(index_url)
        collect_dmg(index_html, index_url, candidates)

        # サブページを追う。version らしき URL（数字を含む）を優先するため降順ソートする
        sub_links = sorted(collect_sub_links(index_html), reverse=True)
        seen = set()
        for href in sub_links:
            sub_url = urljoin(index_url, href)
            if sub_url == index_url or sub_url in seen:
                continue
            seen.add(sub_url)
            try:
                sub_html = fetch(sub_url)
            except Exception:
                continue
            collect_dmg(sub_html, sub_url, candidates)
            if len(seen) >= SUB_PAGE_LIMIT:
                break

        if not candidates:
            result = {"error": "no Pleiades Mac/Java dmg found in index or sub pages"}
        else:
            latest = max(candidates.keys())
            version, build_date = latest
            files = []
            for arch in target_arches:
                if arch in candidates[latest]:
                    files.append(candidates[latest][arch])
            result = {
                "version": version,
                "buildDate": build_date,
                "files": files,
            }
            if not files:
                result["error"] = (
                    f"target arch not found in latest. "
                    f"requested={target_arches} "
                    f"available={sorted(candidates[latest].keys())}"
                )
    except Exception as e:
        result = {"error": f"{type(e).__name__}: {e}"}

    with open(args.output, "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
'''
}
