import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic
import groovy.transform.Field

/*
 * IMAP メールボックスを定期監視し、条件一致したメールを Slack へ通知する
 * Kubernetes コンテナ Agent 向けパイプラインです。
 *
 * 設計方針:
 * - Jenkins の cron 実行でポーリングし、常駐プロセスを持たない
 * - IMAP UID を state JSON に保存して重複通知を防止する
 * - state は PVC マウントに置けるようにし、Pod 再作成後も継続監視できるようにする
 * - キーワード条件は「項目内 OR、項目どうし AND」で扱う
 */

def libBranch = env.CHANGE_BRANCH ?: env.BRANCH_NAME ?: 'main'
def libId = "jqit-lib@${libBranch}"

try {
    library libId
} catch (err) {
    echo "${libId} の読み込みに失敗しました。jqit-lib@main へフォールバックします。"
    library 'jqit-lib@main'
}

@Field String DEFAULT_AGENT_IMAGE = 'python:3.14-slim'
@Field String DEFAULT_STATE_MOUNT_PATH = '/mail-monitor-state'
@Field String DEFAULT_STATE_FILE_NAME = 'imap-last-uid.json'
@Field String DEFAULT_IMAP_CREDENTIALS_ID = 'mail-monitor-imap'
@Field String DEFAULT_SLACK_TOKEN_CREDENTIAL_ID = 'slack-bot-token'

pipeline {
    agent none

    triggers {
        cron('TZ=Asia/Tokyo\nH/5 * * * *')
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
            name: 'IMAP_HOST',
            defaultValue: '',
            description: '監視対象 IMAP サーバーのホスト名'
        )
        string(
            name: 'IMAP_PORT',
            defaultValue: '993',
            description: 'IMAP ポート番号（通常は 993）'
        )
        booleanParam(
            name: 'IMAP_USE_SSL',
            defaultValue: true,
            description: 'IMAP over SSL (IMAPS) で接続する'
        )
        string(
            name: 'IMAP_MAILBOX',
            defaultValue: 'INBOX',
            description: '監視対象メールボックス名'
        )
        string(
            name: 'IMAP_CREDENTIALS_ID',
            defaultValue: DEFAULT_IMAP_CREDENTIALS_ID,
            description: 'IMAP 用 Jenkins Credentials ID（Username with password）'
        )
        string(
            name: 'SLACK_TOKEN_CREDENTIAL_ID',
            defaultValue: DEFAULT_SLACK_TOKEN_CREDENTIAL_ID,
            description: 'Slack Bot/User Token の Jenkins Credentials ID（Secret text）'
        )
        string(
            name: 'SLACK_CHANNEL',
            defaultValue: '',
            description: 'Slack の通知先チャンネル名またはチャンネル ID（例: #alerts / C0123456789）'
        )
        string(
            name: 'SUBJECT_KEYWORDS',
            defaultValue: '',
            description: '件名で検知したいキーワード（カンマ区切り、部分一致・大文字小文字無視）'
        )
        string(
            name: 'RECIPIENT_KEYWORDS',
            defaultValue: '',
            description: '宛先(To/Cc/Delivered-To)で検知したいキーワード（カンマ区切り）'
        )
        string(
            name: 'FROM_KEYWORDS',
            defaultValue: '',
            description: '送信元で検知したいキーワード（カンマ区切り）'
        )
        string(
            name: 'BODY_KEYWORDS',
            defaultValue: '',
            description: '本文で検知したいキーワード（カンマ区切り）'
        )
        string(
            name: 'ANYWHERE_KEYWORDS',
            defaultValue: '',
            description: '件名/宛先/送信元/本文のどこかで検知したいキーワード（カンマ区切り）'
        )
        choice(
            name: 'INITIAL_SYNC_MODE',
            choices: ['latest', 'scan-all'].join('\n'),
            description: 'state が無い初回実行時の扱い。latest=現在最新UIDから開始 / scan-all=既存メールも走査'
        )
        string(
            name: 'PVC_CLAIM_NAME',
            defaultValue: '',
            description: 'state を永続化する PVC 名。空欄時は emptyDir を使うため Pod 再作成で state が消えます'
        )
        string(
            name: 'STATE_VOLUME_MOUNT_PATH',
            defaultValue: DEFAULT_STATE_MOUNT_PATH,
            description: 'state 用ボリュームのマウント先'
        )
        string(
            name: 'STATE_FILE_PATH',
            defaultValue: '',
            description: 'state JSON ファイルの保存先。空欄時は STATE_VOLUME_MOUNT_PATH 配下へ保存'
        )
        string(
            name: 'MAX_SLACK_ITEMS',
            defaultValue: '10',
            description: 'Slack 通知に掲載する最大メール件数'
        )
        booleanParam(
            name: 'TEST_NOTIFICATIONS_ONLY',
            defaultValue: false,
            description: 'true の場合は Slack 通知テストのみ行い、IMAP 監視や state 更新をスキップ'
        )
        booleanParam(
            name: 'DRY_RUN',
            defaultValue: false,
            description: 'true の場合は監視結果を表示するだけで Slack 通知も state 更新も行わない'
        )
    }

    stages {
        stage('Monitor Mailbox') {
            agent {
                kubernetes {
                    defaultContainer 'mail'
                    yaml buildMailMonitorPodYaml()
                }
            }

            steps {
                script {
                    def config = buildMonitorConfig()
                    def stateDir = sh(
                        script: "dirname '${shellQuote(config.stateFilePath)}'",
                        returnStdout: true
                    ).trim()

                    echo '========================================='
                    echo 'メール監視パイプライン'
                    echo '========================================='
                    echo "IMAP Host             : ${config.imapHost}"
                    echo "IMAP Port             : ${config.imapPort}"
                    echo "IMAP SSL              : ${config.imapUseSsl}"
                    echo "Mailbox               : ${config.imapMailbox}"
                    echo "IMAP Credentials ID   : ${config.imapCredentialsId}"
                    echo "Slack Credential ID   : ${config.slackTokenCredentialId}"
                    echo "Slack Channel         : ${config.slackChannel}"
                    echo "State File            : ${config.stateFilePath}"
                    echo "初回同期モード          : ${config.initialSyncMode}"
                    echo "PVC Claim             : ${config.pvcClaimName ?: '未指定（emptyDir）'}"
                    echo "キーワード条件           : ${describeFilters(config.filters)}"
                    echo "DRY_RUN               : ${config.dryRun}"
                    echo "通知テストのみ            : ${config.testNotificationsOnly}"
                    echo '========================================='

                    if (!config.pvcClaimName) {
                        echo '[WARN] PVC_CLAIM_NAME が空です。Pod が再作成されると state が失われ、同じメールを再通知する可能性があります。'
                    }

                    sh "mkdir -p '${shellQuote(stateDir)}'"

                    writeFile(file: '.mail-monitor.py', text: buildMailMonitorPythonScript())
                    writeFile(
                        file: '.mail-monitor-config.json',
                        text: JsonOutput.prettyPrint(JsonOutput.toJson([
                            imapHost         : config.imapHost,
                            imapPort         : config.imapPort,
                            imapUseSsl       : config.imapUseSsl,
                            mailbox          : config.imapMailbox,
                            stateFile        : config.stateFilePath,
                            initialSyncMode  : config.initialSyncMode,
                            subjectKeywords  : config.filters.subject,
                            recipientKeywords: config.filters.recipient,
                            fromKeywords     : config.filters.from,
                            bodyKeywords     : config.filters.body,
                            anywhereKeywords : config.filters.anywhere
                        ]))
                    )

                    if (config.testNotificationsOnly) {
                        withCredentials([string(credentialsId: config.slackTokenCredentialId, variable: 'SLACK_TOKEN')]) {
                            postSlackMessage(
                                buildSlackPayload([
                                    channel   : config.slackChannel,
                                    title     : 'メール監視通知のテスト',
                                    bodyLines : [
                                        "IMAP Host: ${config.imapHost}",
                                        "Mailbox: ${config.imapMailbox}",
                                        "条件: ${describeFilters(config.filters)}",
                                        "State File: ${config.stateFilePath}",
                                        'この通知が届けば Slack Token 設定は有効です。'
                                    ],
                                    jobUrl    : env.JOB_URL ?: '',
                                    buildUrl  : env.BUILD_URL ?: ''
                                ]),
                                'SLACK_TOKEN'
                            )
                        }

                        echo 'Slack テスト通知を送信しました。'
                        return
                    }

                    withCredentials([
                        usernamePassword(
                            credentialsId: config.imapCredentialsId,
                            usernameVariable: 'IMAP_USERNAME',
                            passwordVariable: 'IMAP_PASSWORD'
                        )
                    ]) {
                        sh '''#!/bin/bash
                            set -euo pipefail
                            python --version
                            python .mail-monitor.py \
                              --config .mail-monitor-config.json \
                              --output .mail-monitor-result.json
                        '''
                    }

                    def result = new JsonSlurperClassic().parseText(readFile('.mail-monitor-result.json'))
                    writeFile(
                        file: 'email-monitor-result.json',
                        text: JsonOutput.prettyPrint(JsonOutput.toJson(result))
                    )
                    archiveArtifacts artifacts: 'email-monitor-result.json', onlyIfSuccessful: false

                    echo "新規メール数 : ${result.new_message_count}"
                    echo "一致件数   : ${result.matched_count}"
                    echo "前回UID   : ${result.previous_last_uid}"
                    echo "今回UID   : ${result.new_last_uid}"

                    if (config.dryRun) {
                        echo '[DRY_RUN] Slack 通知と state 更新をスキップします。'
                        return
                    }

                    if (result.bootstrap == true) {
                        saveStateFile(config.stateFilePath, result, config)
                        echo "初回同期を完了しました。最新 UID=${result.new_last_uid} を state へ保存しました。"
                        return
                    }

                    if ((result.matched_count ?: 0) as Integer > 0) {
                        withCredentials([string(credentialsId: config.slackTokenCredentialId, variable: 'SLACK_TOKEN')]) {
                            postSlackMessage(
                                buildMatchNotificationPayload(config, result),
                                'SLACK_TOKEN'
                            )
                        }
                        echo "Slack へ ${result.matched_count} 件の一致メールを通知しました。"
                    } else {
                        echo '条件に一致するメールはありませんでした。'
                    }

                    saveStateFile(config.stateFilePath, result, config)
                    echo "state を更新しました: ${config.stateFilePath}"
                }
            }

            post {
                always {
                    sh '''
                        rm -f .mail-monitor.py .mail-monitor-config.json .mail-monitor-result.json .email-monitor-slack.json 2>/dev/null || true
                    '''
                }
            }
        }
    }

    post {
        success {
            echo 'メール監視パイプラインが正常に完了しました。'
        }
        failure {
            echo 'メール監視パイプラインが失敗しました。IMAP 接続、Credential、PVC、Slack Token / Channel 設定を確認してください。'
        }
    }
}

def buildMonitorConfig() {
    def testNotificationsOnly = params.TEST_NOTIFICATIONS_ONLY == true
    def imapHost = (params.IMAP_HOST ?: '').trim()
    def slackTokenCredentialId = (params.SLACK_TOKEN_CREDENTIAL_ID ?: '').trim()
    if (!slackTokenCredentialId) {
        error('SLACK_TOKEN_CREDENTIAL_ID は必須です。')
    }

    def slackChannel = (params.SLACK_CHANNEL ?: '').trim()
    if (!slackChannel) {
        error('SLACK_CHANNEL は必須です。')
    }

    def imapCredentialsId = (params.IMAP_CREDENTIALS_ID ?: '').trim()
    if (!testNotificationsOnly && !imapHost) {
        error('IMAP_HOST は必須です。')
    }
    if (!testNotificationsOnly && !imapCredentialsId) {
        error('IMAP_CREDENTIALS_ID は必須です。')
    }

    def filters = [
        subject  : parseCsv(params.SUBJECT_KEYWORDS),
        recipient: parseCsv(params.RECIPIENT_KEYWORDS),
        from     : parseCsv(params.FROM_KEYWORDS),
        body     : parseCsv(params.BODY_KEYWORDS),
        anywhere : parseCsv(params.ANYWHERE_KEYWORDS)
    ]

    if (!testNotificationsOnly && filters.values().every { (it ?: []).isEmpty() }) {
        error('件名 / 宛先 / 送信元 / 本文 / Anywhere のいずれかに1つ以上キーワードを指定してください。')
    }

    def stateMountPath = ((params.STATE_VOLUME_MOUNT_PATH ?: DEFAULT_STATE_MOUNT_PATH) as String).trim()
    if (!stateMountPath) {
        error('STATE_VOLUME_MOUNT_PATH は空にできません。')
    }

    def stateFilePath = (params.STATE_FILE_PATH ?: '').trim()
    if (!stateFilePath) {
        stateFilePath = "${stateMountPath}/${DEFAULT_STATE_FILE_NAME}"
    }

    return [
        imapHost               : imapHost ?: '(not configured)',
        imapPort               : toBoundedInt(params.IMAP_PORT, 993, 1, 65535),
        imapUseSsl             : params.IMAP_USE_SSL == true,
        imapMailbox            : (((params.IMAP_MAILBOX ?: 'INBOX') as String).trim() ?: 'INBOX'),
        imapCredentialsId      : imapCredentialsId,
        slackTokenCredentialId : slackTokenCredentialId,
        slackChannel           : slackChannel,
        filters                : filters,
        initialSyncMode        : ((params.INITIAL_SYNC_MODE ?: 'latest') as String).trim() == 'scan-all' ? 'scan-all' : 'latest',
        pvcClaimName           : (params.PVC_CLAIM_NAME ?: '').trim(),
        stateMountPath         : stateMountPath,
        stateFilePath          : stateFilePath,
        maxSlackItems          : toBoundedInt(params.MAX_SLACK_ITEMS, 10, 1, 50),
        dryRun                 : params.DRY_RUN == true,
        testNotificationsOnly  : testNotificationsOnly
    ]
}

def buildMailMonitorPodYaml() {
    def mountPath = ((params.STATE_VOLUME_MOUNT_PATH ?: DEFAULT_STATE_MOUNT_PATH) as String).trim() ?: DEFAULT_STATE_MOUNT_PATH
    def pvcClaimName = (params.PVC_CLAIM_NAME ?: '').trim()

    def volumeSource = pvcClaimName
        ? """persistentVolumeClaim:
        claimName: "${pvcClaimName}" """
        : 'emptyDir: {}'

    return """---
apiVersion: v1
kind: Pod
spec:
  restartPolicy: Never
  containers:
    - name: mail
      image: "${DEFAULT_AGENT_IMAGE}"
      command:
        - cat
      tty: true
      resources:
        requests:
          cpu: "250m"
          memory: "512Mi"
        limits:
          cpu: "1"
          memory: "1Gi"
      volumeMounts:
        - name: mail-state
          mountPath: "${mountPath}"
  volumes:
    - name: mail-state
      ${volumeSource}
"""
}

def parseCsv(String raw) {
    return (raw ?: '')
        .split(',')
        .collect { it.trim() }
        .findAll { it }
        .unique()
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

def shellQuote(String value) {
    return (value ?: '').replace("'", "'\"'\"'")
}

String describeFilters(Map filters) {
    def parts = []

    if (filters.subject) {
        parts << "subject=${filters.subject.join(' | ')}"
    }
    if (filters.recipient) {
        parts << "recipient=${filters.recipient.join(' | ')}"
    }
    if (filters.from) {
        parts << "from=${filters.from.join(' | ')}"
    }
    if (filters.body) {
        parts << "body=${filters.body.join(' | ')}"
    }
    if (filters.anywhere) {
        parts << "anywhere=${filters.anywhere.join(' | ')}"
    }

    return parts ? parts.join(', ') : '未指定'
}

String truncateText(def raw, int maxLen = 160) {
    def text = (raw ?: '').toString().trim()
    if (!text) {
        return ''
    }

    if (text.length() <= maxLen) {
        return text
    }

    return text.substring(0, maxLen) + '...'
}

String buildSlackPayload(Map payloadArgs) {
    def lines = []
    lines << (payloadArgs.title ?: 'メール監視通知').toString()
    lines << ''

    (payloadArgs.bodyLines ?: []).each { line ->
        lines << line.toString()
    }

    if (payloadArgs.jobUrl) {
        lines << ''
        lines << "Jenkins Job: ${payloadArgs.jobUrl}"
    }
    if (payloadArgs.buildUrl) {
        lines << "Build URL: ${payloadArgs.buildUrl}"
    }

    return JsonOutput.toJson([
        channel      : payloadArgs.channel?.toString(),
        text         : lines.join('\n').trim(),
        mrkdwn       : true,
        unfurl_links : false,
        unfurl_media : false
    ])
}

String buildMatchNotificationPayload(Map config, Map result) {
    def lines = []
    lines << "Mailbox: ${result.mailbox}"
    lines << "条件: ${describeFilters(config.filters)}"
    lines << "新規メール: ${result.new_message_count} 件"
    lines << "一致メール: ${result.matched_count} 件"
    lines << "UID: ${result.previous_last_uid} -> ${result.new_last_uid}"

    def matches = (result.matches ?: []) as List
    int limit = config.maxSlackItems as Integer

    if (matches) {
        lines << ''
    }

    matches.take(limit).eachWithIndex { match, idx ->
        def matchedFields = ((match.matched_fields ?: []) as List).join(', ')
        lines << "${idx + 1}. [UID ${match.uid}] ${truncateText(match.subject, 120)}"
        lines << "   From: ${truncateText(match.from, 120)}"
        lines << "   To: ${truncateText(match.recipients, 140)}"
        lines << "   Date: ${truncateText(match.date, 80)}"
        if (matchedFields) {
            lines << "   Matched: ${matchedFields}"
        }
        if (match.message_id) {
            lines << "   Message-ID: ${truncateText(match.message_id, 120)}"
        }
    }

    if (matches.size() > limit) {
        lines << ''
        lines << "他 ${matches.size() - limit} 件はアーティファクト `email-monitor-result.json` を参照してください。"
    }

    return buildSlackPayload([
        channel  : config.slackChannel,
        title    : "メール監視で ${result.matched_count} 件ヒットしました",
        bodyLines: lines,
        jobUrl   : env.JOB_URL ?: '',
        buildUrl : env.BUILD_URL ?: ''
    ])
}

def saveStateFile(String stateFilePath, Map result, Map config) {
    def payload = JsonOutput.prettyPrint(JsonOutput.toJson([
        version    : 1,
        imapHost   : config.imapHost,
        mailbox    : result.mailbox ?: config.imapMailbox,
        last_uid   : (result.new_last_uid ?: 0) as Integer,
        updated_at : new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone('Asia/Tokyo'))
    ]))
    writeFile(file: stateFilePath, text: payload + '\n')
}

def postSlackMessage(String payloadText, String credentialEnvVar) {
    writeFile(file: '.email-monitor-slack.json', text: payloadText)

    sh """#!/bin/bash
        set -euo pipefail
        export SLACK_API_PAYLOAD_FILE='.email-monitor-slack.json'
        export SLACK_TOKEN="\${${credentialEnvVar}}"

        python - <<'PY'
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
    response_body = response.read().decode('utf-8')

slack_response = json.loads(response_body)
if not slack_response.get('ok'):
    raise SystemExit(f"Slack API error: {slack_response.get('error', 'unknown_error')}")

PY
    """
}

String buildMailMonitorPythonScript() {
    return '''#!/usr/bin/env python3
import argparse
import email
import html
import imaplib
import json
import os
import re
import ssl
from email import policy
from email.header import decode_header, make_header


def decode_header_value(value):
    if not value:
        return ""

    try:
        return str(make_header(decode_header(value)))
    except Exception:
        parts = []
        for chunk, charset in decode_header(value):
            if isinstance(chunk, bytes):
                parts.append(chunk.decode(charset or "utf-8", errors="replace"))
            else:
                parts.append(str(chunk))
        return "".join(parts)


def truncate_text(value, max_len=500):
    text = (value or "").strip()
    if len(text) <= max_len:
        return text
    return text[:max_len] + "..."


def load_state(path):
    if not os.path.exists(path):
        return {"exists": False, "last_uid": 0}

    with open(path, "r", encoding="utf-8") as fh:
        state = json.load(fh)

    last_uid = int(state.get("last_uid", 0) or 0)
    return {"exists": True, "last_uid": last_uid}


def contains_any(text, keywords):
    normalized = (text or "").lower()
    for keyword in keywords:
        if keyword.lower() in normalized:
            return True
    return False


def html_to_text(raw_html):
    if not raw_html:
        return ""

    text = re.sub(r"(?is)<(script|style).*?>.*?</\\1>", " ", raw_html)
    text = re.sub(r"(?s)<[^>]+>", " ", text)
    text = re.sub(r"\\s+", " ", html.unescape(text))
    return text.strip()


def extract_body_text(message):
    plain_text_parts = []
    html_parts = []

    for part in message.walk():
        if part.is_multipart():
            continue

        disposition = (part.get_content_disposition() or "").lower()
        if disposition == "attachment":
            continue

        content_type = part.get_content_type()

        try:
            content = part.get_content()
        except Exception:
            payload = part.get_payload(decode=True) or b""
            charset = part.get_content_charset() or "utf-8"
            content = payload.decode(charset, errors="replace")

        if not content:
            continue

        if content_type == "text/plain":
            plain_text_parts.append(str(content))
        elif content_type == "text/html":
            html_parts.append(str(content))

    if plain_text_parts:
        return "\\n".join(plain_text_parts).strip()

    if html_parts:
        return html_to_text("\\n".join(html_parts))

    try:
        if message.get_content_maintype() == "text":
            content = message.get_content()
            if isinstance(content, str):
                return content.strip()
    except Exception:
        pass

    return ""


def parse_message(raw_message):
    message = email.message_from_bytes(raw_message, policy=policy.default)

    subject = decode_header_value(message.get("Subject", ""))
    sender = decode_header_value(message.get("From", ""))

    recipients = []
    for header_name in ("To", "Cc", "Delivered-To"):
        value = decode_header_value(message.get(header_name, ""))
        if value:
            recipients.append(value)

    date_value = decode_header_value(message.get("Date", ""))
    message_id = decode_header_value(message.get("Message-ID", ""))
    body_text = extract_body_text(message)

    return {
        "subject": subject,
        "from": sender,
        "recipients": ", ".join(recipients),
        "date": date_value,
        "message_id": message_id,
        "body": body_text,
    }


def evaluate_match(meta, filters):
    matched_fields = []

    subject_keywords = filters.get("subject", [])
    if subject_keywords:
        if contains_any(meta["subject"], subject_keywords):
            matched_fields.append("subject")
        else:
            return False, []

    recipient_keywords = filters.get("recipient", [])
    if recipient_keywords:
        if contains_any(meta["recipients"], recipient_keywords):
            matched_fields.append("recipient")
        else:
            return False, []

    from_keywords = filters.get("from", [])
    if from_keywords:
        if contains_any(meta["from"], from_keywords):
            matched_fields.append("from")
        else:
            return False, []

    body_keywords = filters.get("body", [])
    if body_keywords:
        if contains_any(meta["body"], body_keywords):
            matched_fields.append("body")
        else:
            return False, []

    anywhere_keywords = filters.get("anywhere", [])
    if anywhere_keywords:
        anywhere_text = " ".join(
            [
                meta["subject"],
                meta["from"],
                meta["recipients"],
                meta["body"],
            ]
        )
        if contains_any(anywhere_text, anywhere_keywords):
            matched_fields.append("anywhere")
        else:
            return False, []

    return True, matched_fields


def fetch_message(imap_conn, uid):
    status, data = imap_conn.uid("FETCH", str(uid), "(RFC822)")
    if status != "OK":
        raise RuntimeError(f"failed to fetch uid={uid}: {status}")

    for item in data:
        if isinstance(item, tuple) and len(item) >= 2 and item[1]:
            return item[1]

    raise RuntimeError(f"raw message not found for uid={uid}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    with open(args.config, "r", encoding="utf-8") as fh:
        config = json.load(fh)

    state = load_state(config["stateFile"])
    previous_last_uid = int(state.get("last_uid", 0) or 0)

    filters = {
        "subject": config.get("subjectKeywords") or [],
        "recipient": config.get("recipientKeywords") or [],
        "from": config.get("fromKeywords") or [],
        "body": config.get("bodyKeywords") or [],
        "anywhere": config.get("anywhereKeywords") or [],
    }

    if config.get("imapUseSsl", True):
        context = ssl.create_default_context()
        imap_conn = imaplib.IMAP4_SSL(
            config["imapHost"],
            int(config["imapPort"]),
            ssl_context=context,
        )
    else:
        imap_conn = imaplib.IMAP4(config["imapHost"], int(config["imapPort"]))

    try:
        imap_conn.login(os.environ["IMAP_USERNAME"], os.environ["IMAP_PASSWORD"])

        status, _ = imap_conn.select(config["mailbox"], readonly=True)
        if status != "OK":
            raise RuntimeError(f"failed to select mailbox={config['mailbox']}: {status}")

        status, data = imap_conn.uid("SEARCH", None, "ALL")
        if status != "OK":
            raise RuntimeError(f"failed to search mailbox={config['mailbox']}: {status}")

        uid_tokens = data[0].split() if data and data[0] else []
        uids = [int(token) for token in uid_tokens]
        latest_uid = max(uids) if uids else previous_last_uid

        if not state["exists"] and config.get("initialSyncMode") == "latest":
            result = {
                "bootstrap": True,
                "mailbox": config["mailbox"],
                "previous_last_uid": previous_last_uid,
                "new_last_uid": latest_uid,
                "new_message_count": 0,
                "matched_count": 0,
                "matches": [],
            }
            with open(args.output, "w", encoding="utf-8") as fh:
                json.dump(result, fh, ensure_ascii=False, indent=2)
            return

        new_uids = [uid for uid in uids if uid > previous_last_uid]
        matches = []

        for uid in new_uids:
            raw_message = fetch_message(imap_conn, uid)
            meta = parse_message(raw_message)
            matched, matched_fields = evaluate_match(meta, filters)

            if not matched:
                continue

            matches.append(
                {
                    "uid": uid,
                    "subject": truncate_text(meta["subject"], 300),
                    "from": truncate_text(meta["from"], 300),
                    "recipients": truncate_text(meta["recipients"], 400),
                    "date": truncate_text(meta["date"], 120),
                    "message_id": truncate_text(meta["message_id"], 300),
                    "matched_fields": matched_fields,
                }
            )

        result = {
            "bootstrap": False,
            "mailbox": config["mailbox"],
            "previous_last_uid": previous_last_uid,
            "new_last_uid": latest_uid,
            "new_message_count": len(new_uids),
            "matched_count": len(matches),
            "matches": matches,
        }

        with open(args.output, "w", encoding="utf-8") as fh:
            json.dump(result, fh, ensure_ascii=False, indent=2)
    finally:
        try:
            imap_conn.close()
        except Exception:
            pass
        try:
            imap_conn.logout()
        except Exception:
            pass


if __name__ == "__main__":
    main()
'''
}
