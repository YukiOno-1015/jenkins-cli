/*
 * Jenkins から安全に SSH 実行を行うための Shared Library です。
 *
 * 目的:
 * - 受け取った command / script を安全にリモートへ渡す
 * - StrictHostKeyChecking の有無を切り替え可能にする
 * - `returnStdout` により、参照専用の取得処理にも更新処理にも再利用できるようにする
 */

/**
 * シェル引数として安全に埋め込めるよう、値を単一引用符でエスケープする。
 */
def shellQuote(String value) {
    return "'${(value ?: '').replace("'", "'\"'\"'")}'"
}

/**
 * `command` または `script` のどちらか一方だけを受け取り、
 * リモート側で `set -euo pipefail` を有効にした実行スクリプトを構築する。
 */
def buildRemoteScript(Map args) {
    def command = args.command?.toString()
    def script = args.script?.toString()

    if (command && script) {
        error('command と script は同時に指定できません。どちらか一方のみ指定してください。')
    }

    if (!command && !script) {
        error('command または script のいずれかを指定してください。')
    }

    def payload = script ?: command

    return """
set -euo pipefail

${payload}
""".trim()
}

/**
 * SSH コマンド実行のエントリポイント。
 * 必須項目は `host`, `user`, `sshCredentialsId` で、必要に応じて `port`,
 * `knownHost`, `useSudo`, `strictHostKeyChecking`, `returnStdout` を上書きできる。
 */
def call(Map args = [:]) {
    def host = args.host?.toString()?.trim()
    def user = args.user?.toString()?.trim()
    def sshCredentialsId = args.sshCredentialsId?.toString()?.trim()

    if (!host) {
        error('host は必須パラメータです。')
    }

    if (!user) {
        error('user は必須パラメータです。')
    }

    if (!sshCredentialsId) {
        error('sshCredentialsId は必須パラメータです。')
    }

    def port = args.get('port', 22) as Integer
    def connectTimeoutSeconds = args.get('connectTimeoutSeconds', 10) as Integer
    def knownHost = args.get('knownHost', host)?.toString()?.trim()
    def useSudo = (args.get('useSudo', false) as Boolean)
    def returnStdout = (args.get('returnStdout', false) as Boolean)
    def strictHostKeyChecking = (args.get('strictHostKeyChecking', true) as Boolean)
    def remoteScript = buildRemoteScript(args)
    def remoteEntrypoint = useSudo ? 'sudo -n bash -s' : 'bash -s'
    def executionMode = args.script ? 'script' : 'command'

    echo "リモート SSH 実行を開始します: ${host}（ユーザー: ${user}${useSudo ? '、sudo使用' : ''}）"
    echo "SSH接続設定: port=${port}, knownHost=${knownHost}, ホスト鍵検証=${strictHostKeyChecking}, 実行モード=${executionMode}, 標準出力返却=${returnStdout}"

    sshagent(credentials: [sshCredentialsId]) {
        def sshScript = """#!/bin/bash
set -euo pipefail

SSH_TARGET_HOST=${shellQuote(host)}
SSH_TARGET_USER=${shellQuote(user)}
SSH_PORT=${shellQuote(port.toString())}
SSH_KNOWN_HOST=${shellQuote(knownHost)}

if ${strictHostKeyChecking ? 'true' : 'false'}; then
  echo "ホスト鍵検証を有効化し、known_hosts を更新します"
  mkdir -p ~/.ssh
  chmod 700 ~/.ssh
  touch ~/.ssh/known_hosts
  chmod 600 ~/.ssh/known_hosts

  echo "known_hosts に \${SSH_KNOWN_HOST} を追加します（rsa/ecdsa/ed25519）"
  ssh-keyscan -p "\${SSH_PORT}" -t rsa,ecdsa,ed25519 -H "\${SSH_KNOWN_HOST}" >> ~/.ssh/known_hosts 2>/dev/null || true
  sort -u ~/.ssh/known_hosts -o ~/.ssh/known_hosts || true

  SSH_HOST_KEY_OPTIONS=(
    -o StrictHostKeyChecking=yes
    -o UserKnownHostsFile="\$HOME/.ssh/known_hosts"
  )
else
  echo "警告: ホスト鍵検証を無効化して接続します"
  SSH_HOST_KEY_OPTIONS=(
    -o StrictHostKeyChecking=no
    -o UserKnownHostsFile=/dev/null
  )
fi

SSH_COMMON_OPTIONS=(
  -o BatchMode=yes
  -o ConnectTimeout=${connectTimeoutSeconds}
)

echo "SSH接続を開始: \${SSH_TARGET_USER}@\${SSH_TARGET_HOST}:\${SSH_PORT}"
ssh "\${SSH_COMMON_OPTIONS[@]}" "\${SSH_HOST_KEY_OPTIONS[@]}" -p "\${SSH_PORT}" "\${SSH_TARGET_USER}@\${SSH_TARGET_HOST}" ${shellQuote(remoteEntrypoint)} <<'REMOTE_SCRIPT'
${remoteScript}
REMOTE_SCRIPT

echo "SSH接続処理が完了しました"
"""

        if (returnStdout) {
            echo '標準出力を取得して呼び出し元へ返します。'
            def stdout = sh(script: sshScript, returnStdout: true).trim()
            echo "リモート SSH 実行が完了しました（標準出力 ${stdout.length()} 文字）"
            return stdout
        }

        sh(script: sshScript)
        echo 'リモート SSH 実行が完了しました。'
        return null
    }
}
