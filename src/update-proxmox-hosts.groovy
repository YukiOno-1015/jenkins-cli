/*
 * Proxmox ホスト群の「更新候補の監視」と「必要時のみの安全な適用」を行う
 * Declarative Pipeline です。
 *
 * 基本方針:
 * - 通常実行では更新候補の検知だけを行い、結果を JSON と通知で共有する
 * - `APPLY_UPDATES=true` が明示された場合だけ `full-upgrade` を実施する
 * - クラスタ環境では quorum 状態を確認し、安全に更新できるホストだけを対象にする
 *
 * 任意の Jenkins Credentials（Kind: Secret text）:
 *   slack-webhook-url   : Slack Incoming Webhook URL
 *   discord-webhook-url : Discord Webhook URL
 */

import com.cloudbees.groovy.cps.NonCPS

def PROXMOX_HOSTS = [
    'umi',
    'nozomi',
    'maki',
    'eri',
    'nico',
]

def SLACK_WEBHOOK_CREDENTIAL_ID = 'slack-webhook-url'
def DISCORD_WEBHOOK_CREDENTIAL_ID = 'discord-webhook-url'
def DEFAULT_SSH_CREDENTIAL_ID = 'github-ssh'

def proxmoxHostResults = []

// 検知結果を、人が通知で読みやすい要約文へ変換する。
@NonCPS
String summarizePendingUpdates(List results) {
    def hostsWithUpdates = results.findAll { it.hasUpdates }
    if (!hostsWithUpdates) {
        return 'Proxmox ホストに更新候補はありません。'
    }

    return hostsWithUpdates.collect { result ->
        def proxmoxMarker = result.hasProxmoxUpdates ? 'Proxmox更新あり' : 'OS更新あり'
        def rebootMarker = result.rebootRequired ? 'reboot required' : 'reboot not required'
        "- ${result.host}: ${proxmoxMarker}, ${rebootMarker}, quorum=${result.clusterQuorate}"
    }.join('\n')
}

@NonCPS
String escapeJson(String value) {
    if (value == null) {
        return ''
    }

    return value
        .replace('\\', '\\\\')
        .replace('"', '\\"')
        .replace('\b', '\\b')
        .replace('\f', '\\f')
        .replace('\n', '\\n')
        .replace('\r', '\\r')
        .replace('\t', '\\t')
}

@NonCPS
String buildSlackPayload(String title, String body, String jobUrl, String artifactUrl) {
    def text = """${title}

${body}

Jenkins Job: ${jobUrl}
結果JSON: ${artifactUrl}

更新する場合は Jenkins で APPLY_UPDATES=true を指定してください。""".trim()
    return '{"text":"' + escapeJson(text) + '"}'
}

@NonCPS
String buildDiscordPayload(String title, String body, String jobUrl, String artifactUrl) {
    def text = """**${title}**
${body}

Jenkins Job: ${jobUrl}
結果JSON: ${artifactUrl}

更新する場合は Jenkins で APPLY_UPDATES=true を指定してください。""".trim()
    return '{"content":"' + escapeJson(text) + '"}'
}

@NonCPS
String serializeHostResults(List results) {
    def entries = results.collect { result ->
        '{' + [
            '"host":"' + escapeJson(result.host?.toString()) + '"',
            '"isProxmox":' + (result.isProxmox ? 'true' : 'false'),
            '"pveVersion":"' + escapeJson(result.pveVersion?.toString()) + '"',
            '"clusterQuorate":"' + escapeJson(result.clusterQuorate?.toString()) + '"',
            '"clusterNodeCount":' + (result.clusterNodeCount ?: 0),
            '"hasUpdates":' + (result.hasUpdates ? 'true' : 'false'),
            '"hasProxmoxUpdates":' + (result.hasProxmoxUpdates ? 'true' : 'false'),
            '"rebootRequired":' + (result.rebootRequired ? 'true' : 'false'),
            '"upgradable":"' + escapeJson(result.upgradable?.toString()) + '"',
            '"proxmoxUpdates":"' + escapeJson(result.proxmoxUpdates?.toString()) + '"',
        ].join(',') + '}'
    }
    return '[\n' + entries.join(',\n') + '\n]'
}

// Webhook URL を Jenkins Credentials から受け取り、JSON payload を送信する。
def postJsonWebhook = { String payloadText, String credentialEnvVar ->
    def payloadFile = '.proxmox-webhook-payload.json'
    writeFile file: payloadFile, text: payloadText
    sh """#!/bin/bash
        set -euo pipefail
        curl -fsS -X POST \
          -H 'Content-Type: application/json' \
          --data @${payloadFile} \
          "\${${credentialEnvVar}}"
    """
}

def sendNotifications = { String title, String body ->
    def jobUrl = env.JOB_URL ?: env.BUILD_URL ?: ''
    def artifactUrl = env.BUILD_URL ? "${env.BUILD_URL}artifact/proxmox-host-results.json" : ''

    withCredentials([
        string(credentialsId: SLACK_WEBHOOK_CREDENTIAL_ID, variable: 'PROXMOX_SLACK_WEBHOOK_URL'),
        string(credentialsId: DISCORD_WEBHOOK_CREDENTIAL_ID, variable: 'PROXMOX_DISCORD_WEBHOOK_URL'),
    ]) {
        def slackUrl = env.PROXMOX_SLACK_WEBHOOK_URL?.trim()
        def discordUrl = env.PROXMOX_DISCORD_WEBHOOK_URL?.trim()

        if (slackUrl) {
            postJsonWebhook(
                buildSlackPayload(title, body, jobUrl, artifactUrl),
                'PROXMOX_SLACK_WEBHOOK_URL'
            )
        }

        if (discordUrl) {
            postJsonWebhook(
                buildDiscordPayload(title, body, jobUrl, artifactUrl),
                'PROXMOX_DISCORD_WEBHOOK_URL'
            )
        }
    }
}

def buildTestNotificationBody = {
    def jobUrl = env.JOB_URL ?: env.BUILD_URL ?: ''
    def buildUrl = env.BUILD_URL ?: ''

    return """これは Proxmox 通知のテスト送信です。

- Jenkins Job: ${jobUrl}
- Jenkins Build: ${buildUrl}
- Slack Credential: ${SLACK_WEBHOOK_CREDENTIAL_ID}
- Discord Credential: ${DISCORD_WEBHOOK_CREDENTIAL_ID}

この通知が届けば Webhook 設定は有効です。""".trim()
}

// リモート Proxmox ホスト上で実行する調査スクリプトを組み立てる。
// ここでは更新候補・quorum・再起動要否だけを取得し、変更は加えない。
def buildInspectScript = {
    return '''
set -euo pipefail

echo "__META_BEGIN__"

if ! command -v pveversion >/dev/null 2>&1; then
  echo "IS_PROXMOX=false"
  echo "PVE_VERSION=missing"
  echo "CLUSTER_QUORATE=unknown"
  echo "CLUSTER_NODE_COUNT=0"
  echo "HAS_UPDATES=false"
  echo "HAS_PROXMOX_UPDATES=false"
  echo "REBOOT_REQUIRED=false"
  echo "__META_END__"
  echo "__UPGRADABLE_BEGIN__"
  echo "__UPGRADABLE_END__"
  echo "__PROXMOX_UPDATES_BEGIN__"
  echo "__PROXMOX_UPDATES_END__"
  exit 0
fi

PVE_VERSION="$(pveversion | head -n1)"
CLUSTER_QUORATE="unknown"
CLUSTER_NODE_COUNT="0"

if command -v pvecm >/dev/null 2>&1; then
  CLUSTER_QUORATE="$(pvecm status 2>/dev/null | awk -F': ' '/Quorate/ {gsub(/^[ \t]+|[ \t]+$/, "", $2); print $2; exit}' || true)"
    CLUSTER_NODE_COUNT="$(pvecm nodes 2>/dev/null | awk 'NR > 1 && $0 !~ /^[[:space:]]*$/ {count++} END {print count+0}' || true)"
  [ -n "$CLUSTER_QUORATE" ] || CLUSTER_QUORATE="unknown"
  [ -n "$CLUSTER_NODE_COUNT" ] || CLUSTER_NODE_COUNT="0"
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq

UPGRADABLE="$(apt list --upgradable 2>/dev/null | sed '1d' || true)"
PROXMOX_UPDATES="$(printf '%s\n' "$UPGRADABLE" | grep -E '^(ceph|corosync|ifupdown2|libpve[^/]*|proxmox[^/]*|pve[^/]*|qemu-server)/' || true)"

echo "IS_PROXMOX=true"
echo "PVE_VERSION=$PVE_VERSION"
echo "CLUSTER_QUORATE=$CLUSTER_QUORATE"
echo "CLUSTER_NODE_COUNT=$CLUSTER_NODE_COUNT"
echo "HAS_UPDATES=$([ -n "$UPGRADABLE" ] && echo true || echo false)"
echo "HAS_PROXMOX_UPDATES=$([ -n "$PROXMOX_UPDATES" ] && echo true || echo false)"
echo "REBOOT_REQUIRED=$([ -f /var/run/reboot-required ] && echo true || echo false)"
echo "__META_END__"

echo "__UPGRADABLE_BEGIN__"
printf '%s\n' "$UPGRADABLE"
echo "__UPGRADABLE_END__"

echo "__PROXMOX_UPDATES_BEGIN__"
printf '%s\n' "$PROXMOX_UPDATES"
echo "__PROXMOX_UPDATES_END__"
'''.trim()
}

def buildUpgradeScript = { boolean allowReboot ->
    return """
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get full-upgrade -y
apt-get autoremove --purge -y

if [ -f /var/run/reboot-required ]; then
  echo 'REBOOT_REQUIRED=true'
  ${allowReboot ? 'reboot' : "echo 'REBOOT_SKIPPED=true'"}
else
  echo 'REBOOT_REQUIRED=false'
fi
""".trim()
}

def runRemoteScript = { host, remoteScript ->
    def sshCredentialsId = params.SSH_CREDENTIALS_ID?.toString()?.trim() ?: DEFAULT_SSH_CREDENTIAL_ID

    sshagent(credentials: [sshCredentialsId]) {
        return sh(
            script: """
                ssh -o StrictHostKeyChecking=no -o BatchMode=yes -o ConnectTimeout=10 root@${host} 'bash -s' <<'REMOTE_SCRIPT'
${remoteScript}
REMOTE_SCRIPT
            """,
            returnStdout: true
        ).trim()
    }
}

@NonCPS
String extractSection(String raw, String startMarker, String endMarker) {
    def lines = []
    def inSection = false

    raw.split('\n', -1).each { line ->
        if (line == startMarker) {
            inSection = true
            return
        }
        if (line == endMarker) {
            inSection = false
            return
        }
        if (inSection) {
            lines << line
        }
    }

    return lines.join('\n').trim()
}

@NonCPS
Map parseMeta(String raw) {
    def metaBlock = extractSection(raw, '__META_BEGIN__', '__META_END__')
    def meta = [:]

    metaBlock.split('\n', -1).each { line ->
        if (!line.contains('=')) {
            return
        }
        def parts = line.split('=', 2)
        meta[parts[0]] = parts[1]
    }

    return meta
}

// 単一ホストを調査し、後続の通知・更新判定に使う構造化データへまとめる。
def inspectHost = { host ->
    def raw = runRemoteScript(host, buildInspectScript())
    def meta = parseMeta(raw)
    def upgradable = extractSection(raw, '__UPGRADABLE_BEGIN__', '__UPGRADABLE_END__')
    def proxmoxUpdates = extractSection(raw, '__PROXMOX_UPDATES_BEGIN__', '__PROXMOX_UPDATES_END__')

    return [
        host: host,
        isProxmox: meta.IS_PROXMOX == 'true',
        pveVersion: meta.PVE_VERSION ?: 'unknown',
        clusterQuorate: meta.CLUSTER_QUORATE ?: 'unknown',
        clusterNodeCount: (meta.CLUSTER_NODE_COUNT ?: '0') as Integer,
        hasUpdates: meta.HAS_UPDATES == 'true',
        hasProxmoxUpdates: meta.HAS_PROXMOX_UPDATES == 'true',
        rebootRequired: meta.REBOOT_REQUIRED == 'true',
        upgradable: upgradable,
        proxmoxUpdates: proxmoxUpdates,
    ]
}

// 単独ノードなら更新可、クラスタ構成なら quorum が正常な場合のみ更新可と判定する。
def canUpgradeSafely = { Map hostResult ->
    if (!hostResult.isProxmox) {
        return false
    }

    if (hostResult.clusterNodeCount <= 1) {
        return true
    }

    return hostResult.clusterQuorate.equalsIgnoreCase('Yes')
}

pipeline {
    agent { label 'machost' }

    triggers {
        cron('TZ=Asia/Tokyo\nH 4 * * *')
    }

    parameters {
        booleanParam(name: 'TEST_NOTIFICATIONS_ONLY', defaultValue: false, description: 'true の場合は Slack/Discord 通知テストのみを実行し、監視や更新は行わない')
        booleanParam(name: 'APPLY_UPDATES', defaultValue: false, description: 'true の場合のみ Proxmox ホストへ full-upgrade を実行する')
        booleanParam(name: 'ALLOW_REBOOT', defaultValue: false, description: 'reboot required 時に自動再起動する')
        string(name: 'SSH_CREDENTIALS_ID', defaultValue: DEFAULT_SSH_CREDENTIAL_ID, description: 'Proxmox ホストへ SSH 接続する Jenkins Credential ID（sshUserPrivateKey）')
    }

    options {
        timestamps()
        disableConcurrentBuilds()
    }

    stages {
        stage('Test Notifications') {
            when {
                expression { return params.TEST_NOTIFICATIONS_ONLY }
            }
            steps {
                script {
                    sendNotifications(
                        'Proxmox 通知テスト',
                        buildTestNotificationBody()
                    )
                    echo '通知テストを送信しました。'
                }
            }
        }

        stage('Inspect Proxmox Hosts') {
            when {
                expression { return !params.TEST_NOTIFICATIONS_ONLY }
            }
            steps {
                script {
                    def failedHosts = []
                    proxmoxHostResults = []

                    for (host in PROXMOX_HOSTS) {
                        echo "==== ${host} を調査中 ===="
                        try {
                            def hostResult = inspectHost(host)
                            proxmoxHostResults << hostResult

                            echo "[INFO] ${host}: pveバージョン=${hostResult.pveVersion}, 更新あり=${hostResult.hasUpdates}, Proxmox更新あり=${hostResult.hasProxmoxUpdates}, quorate=${hostResult.clusterQuorate}"

                            if (hostResult.proxmoxUpdates) {
                                echo "[PVE-UPDATES] ${host}\n${hostResult.proxmoxUpdates}"
                            }
                        } catch (Exception e) {
                            echo "[ERROR] ${host}: ${e.getMessage()}"
                            failedHosts << host
                        }
                    }

                    writeFile(
                        file: 'proxmox-host-results.json',
                        text: serializeHostResults(proxmoxHostResults)
                    )

                    archiveArtifacts artifacts: 'proxmox-host-results.json', onlyIfSuccessful: false

                    if (!failedHosts.isEmpty()) {
                        error("調査に失敗したホスト: ${failedHosts.join(', ')}")
                    }

                    if (proxmoxHostResults.any { it.hasUpdates }) {
                        currentBuild.result = 'UNSTABLE'
                        echo '1台以上の Proxmox ホストで更新候補が検出されました。'
                    } else {
                        echo 'Proxmox ホストに更新候補はありません。'
                    }
                }
            }
        }

        stage('Apply Proxmox Updates') {
            when {
                expression { return !params.TEST_NOTIFICATIONS_ONLY && params.APPLY_UPDATES }
            }
            steps {
                script {
                    def failedHosts = []

                    for (hostResult in proxmoxHostResults.findAll { it.hasUpdates }) {
                        echo "==== ${hostResult.host} をアップグレード中 ===="

                        if (!canUpgradeSafely(hostResult)) {
                            echo "[SKIP] ${hostResult.host}: クラスタ quorum が健全でないためスキップします。"
                            failedHosts << hostResult.host
                            continue
                        }

                        try {
                            def raw = runRemoteScript(hostResult.host, buildUpgradeScript(params.ALLOW_REBOOT))
                            echo raw
                        } catch (Exception e) {
                            echo "[ERROR] ${hostResult.host}: ${e.getMessage()}"
                            failedHosts << hostResult.host
                        }
                    }

                    if (!failedHosts.isEmpty()) {
                        error("アップグレードに失敗したホスト: ${failedHosts.join(', ')}")
                    }
                }
            }
        }
    }

    post {
        success {
            echo 'Proxmox ホストの調査が正常に完了しました。'
        }
        unstable {
            script {
                sendNotifications(
                    'Proxmox 更新候補を検知しました',
                    """${summarizePendingUpdates(proxmoxHostResults)}

更新する場合は Jenkins ジョブを開き、APPLY_UPDATES=true で実行してください。""".trim()
                )
            }
            echo '更新候補が検出されました。製品物を確認するか、適用する場合は APPLY_UPDATES=true を指定して再実行してください。'
        }
        failure {
            script {
                sendNotifications(
                    'Proxmox 監視/更新ジョブが失敗しました',
                    """ジョブが失敗しました。Jenkins ログを確認してください。

${summarizePendingUpdates(proxmoxHostResults)}""".trim()
                )
            }
            echo 'Proxmox ホストの調査/更新が失敗しました。コンソールログを確認してください。'
        }
    }
}
