/*
 * 複数の Debian / Ubuntu / macOS ホストへ定期的にアップデートを適用する
 * 運用向け Jenkins パイプラインです。
 *
 * 設計方針:
 * - ホスト一覧を役割ごとに明示し、対象範囲をコード上で追いやすくする
 * - root 直ログイン / sudo 実行 / macOS 更新を分岐し、ホストごとの差異を吸収する
 * - 1 台ごとの失敗を記録して、どのホストで問題が起きたか後追いしやすくする
 */

def TARGET_HOSTS = [
    // さくらサーバ
    'sakura-docker',
    // Proxmox VE サーバ
    'nico',
    'umi',
    'nozomi',
    'maki',
    'eri',
    // トンネルサーバ
    'tunnel01',
    'tunnel02',
    // Kubernetes クラスタ
    'k8s-ctrl01',
    'k8s-ctrl02',
    'k8s-ctrl03',
    'k8s-ctrl04',
    'k8s-ctrl05',
    'k8s-node01',
    'k8s-node02',
    'k8s-node03',
    'k8s-node04',
    'k8s-node05',
    'k8s-node06',
    // NFSサーバ
    'nfs01',
    'nfs02',
    // macOS ホスト
    'machost',
    'macmini'
]

def TARGET_HOST_CHOICES = (['ALL'] + TARGET_HOSTS).join('\n')

def SSH_USER = 'honoka'
def MACOS_SSH_USER = 'yukiono'
def DEFAULT_SSH_CREDENTIAL_ID = 'github-ssh'

// macOS ホストは brew update/upgrade で更新する
def MACOS_HOSTS = [
    'machost',
    'macmini'
]

// root で直接入るホスト
def ROOT_LOGIN_HOSTS = [
    'nico',
    'umi',
    'nozomi',
    'maki',
    'eri'
]

// どうしても Valid-Until 回避が必要なホストだけ入れる
// 今回の 404 は mirror mismatch なので、まずは空推奨
def APT_EXPIRED_METADATA_WORKAROUND_HOSTS = [
]

// Debian ミラーの補正 + apt 更新
def REMOTE_PREPARE_APT = '''
set -euo pipefail

if [ -f /etc/apt/sources.list ]; then
  cp -an /etc/apt/sources.list /etc/apt/sources.list.bak.before-jenkins-update || true

  sed -i \
    -e 's|http://ftp\\.jp\\.debian\\.org/debian|https://deb.debian.org/debian|g' \
    -e 's|https://ftp\\.jp\\.debian\\.org/debian|https://deb.debian.org/debian|g' \
    -e 's|http://security\\.debian\\.org|https://security.debian.org|g' \
    /etc/apt/sources.list || true
fi

if [ -d /etc/apt/sources.list.d ]; then
  find /etc/apt/sources.list.d -type f \\( -name "*.list" -o -name "*.sources" \\) -print0 | \
    xargs -0 -r sed -i \
      -e 's|http://ftp\\.jp\\.debian\\.org/debian|https://deb.debian.org/debian|g' \
      -e 's|https://ftp\\.jp\\.debian\\.org/debian|https://deb.debian.org/debian|g' \
      -e 's|http://security\\.debian\\.org|https://security.debian.org|g' || true
fi
'''

// Debian / Ubuntu 系ホスト向けの更新コマンドを組み立てる。
// 一部ホストだけに `Check-Valid-Until=false` を適用できるようにしている。
def buildUpdateCommand = { boolean useValidUntilWorkaround = false ->
    def updatePart = useValidUntilWorkaround
        ? 'apt-get -o Acquire::Check-Valid-Until=false update'
        : 'apt-get update'

    return """
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

${REMOTE_PREPARE_APT}

apt-get clean
rm -rf /var/lib/apt/lists/*
${updatePart}
apt-get full-upgrade -y
apt-get autoremove --purge -y
""".trim()
}

// macOS ホストでは Homebrew ベースで更新する。
def buildMacOsUpdateCommand = {
    return '''
set -euo pipefail

if [ -x /opt/homebrew/bin/brew ]; then
    eval "$(/opt/homebrew/bin/brew shellenv)"
elif [ -x /usr/local/bin/brew ]; then
    eval "$(/usr/local/bin/brew shellenv)"
fi

command -v brew >/dev/null 2>&1 || {
    echo "brew コマンドが見つかりません"
    exit 1
}

brew update
brew upgrade
'''.trim()
}

def runWithSshCredentials = { sshCredentialsId, Closure body ->
    def credentialId = sshCredentialsId?.toString()?.trim()
    if (credentialId) {
        sshagent(credentials: [credentialId]) {
            body.call()
        }
        return
    }

    body.call()
}

def runSshAsRoot = { host, remoteScript, sshCredentialsId ->
    runWithSshCredentials(sshCredentialsId) {
        sh """
            ssh -o StrictHostKeyChecking=no -o BatchMode=yes -A root@${host} 'bash -s' <<'REMOTE_SCRIPT'
${remoteScript}
REMOTE_SCRIPT
        """
    }
}

def runSshWithSudo = { host, sshUser, remoteScript, sshCredentialsId ->
    runWithSshCredentials(sshCredentialsId) {
        sh """
            ssh -o StrictHostKeyChecking=no -o BatchMode=yes -A ${sshUser}@${host} 'sudo -n bash -s' <<'REMOTE_SCRIPT'
${remoteScript}
REMOTE_SCRIPT
        """
    }
}

def runSshAsUser = { host, sshUser, remoteScript, sshCredentialsId ->
    runWithSshCredentials(sshCredentialsId) {
        sh """
            ssh -o StrictHostKeyChecking=no -o BatchMode=yes -A ${sshUser}@${host} 'bash -s' <<'REMOTE_SCRIPT'
${remoteScript}
REMOTE_SCRIPT
        """
    }
}

// ホスト種別に応じて適切な SSH 実行方式と更新コマンドを選択する。
def runUpdateOnHost = { host, sshUser, macOsSshUser, aptExpiredMetadataWorkaroundHosts, sshCredentialsId ->
    echo "==== ${host} を更新中 ===="

    if (MACOS_HOSTS.contains(host)) {
        runSshAsUser(host, macOsSshUser, buildMacOsUpdateCommand(), sshCredentialsId)
        return
    }

    def useWorkaround = aptExpiredMetadataWorkaroundHosts.contains(host)
    def remoteScript = buildUpdateCommand(useWorkaround)

    if (ROOT_LOGIN_HOSTS.contains(host)) {
        runSshAsRoot(host, remoteScript, sshCredentialsId)
        return
    }

    runSshWithSudo(host, sshUser, remoteScript, sshCredentialsId)
}

pipeline {
    agent any

    triggers {
        cron('TZ=Asia/Tokyo\nH 3 * * *')
    }

    parameters {
        choice(name: 'TARGET_HOST', choices: TARGET_HOST_CHOICES, description: '更新対象ホストを選択（ALL で全ホスト実行）')
        string(name: 'SSH_CREDENTIALS_ID', defaultValue: DEFAULT_SSH_CREDENTIAL_ID, description: 'SSH接続に使う Jenkins Credential ID（空欄ならsshagentを使わず実行）')
    }

    options {
        timestamps()
    }

    stages {
        stage('Update All machosts') {
            steps {
                script {
                    def failedHosts = []
                    def targetHosts = params.TARGET_HOST == 'ALL' ? TARGET_HOSTS : [params.TARGET_HOST]
                    def sshCredentialsId = params.SSH_CREDENTIALS_ID?.toString()?.trim()

                    echo "対象ホスト: ${targetHosts.join(', ')}"
                    echo "SSH 認証情報: ${sshCredentialsId ?: '未指定（sshagent なし）'}"

                    for (host in targetHosts) {
                        try {
                            runUpdateOnHost(
                                host,
                                SSH_USER,
                                MACOS_SSH_USER,
                                APT_EXPIRED_METADATA_WORKAROUND_HOSTS,
                                sshCredentialsId
                            )
                            echo "[成功] ${host}"
                        } catch (Exception e) {
                            echo "[失敗] ${host}: ${e.getMessage()}"
                            failedHosts << host
                        }
                    }

                    if (!failedHosts.isEmpty()) {
                        error("更新に失敗したホスト: ${failedHosts.join(', ')}")
                    }
                }
            }
        }
    }

    post {
        success {
            echo 'すべてのホストを正常に更新しました。'
        }
        failure {
            echo '一部のホストの更新に失敗しました。ログを確認してください。'
        }
    }
}
