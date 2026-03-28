// Jenkins用: 複数machostサーバにsshで自動アップデートを定期実行
// sakura-docker のみ sudo パスワードを Jenkins Credentials から利用

def TARGET_HOSTS = [
    'sakura-docker',
    'nico',
    'umi',
    'nozomi',
    'maki',
    'eri',
    'tunnel01',
    'tunnel02',
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
    'nfs01',
    'nfs02',
]

def SSH_USER = 'honoka'
def SAKURA_DOCKER_SUDO_PASSWORD_CREDENTIAL_ID = 'sakura-docker-sudo-password'

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

def runSshAsRoot = { host, remoteScript ->
    sh """
        ssh -o StrictHostKeyChecking=no -o BatchMode=yes -A root@${host} 'bash -s' <<'REMOTE_SCRIPT'
${remoteScript}
REMOTE_SCRIPT
    """
}

def runSshWithSudo = { host, sshUser, remoteScript ->
    sh """
        ssh -o StrictHostKeyChecking=no -o BatchMode=yes -A ${sshUser}@${host} 'sudo -n bash -s' <<'REMOTE_SCRIPT'
${remoteScript}
REMOTE_SCRIPT
    """
}

def runSshWithPasswordSudo = { host, sshUser, credentialId, remoteScript ->
    withCredentials([string(credentialsId: credentialId, variable: 'SUDO_PASSWORD')]) {
        sh """
            set +x
            CLEAN_SUDO_PASSWORD="\$(printf '%s' "\$SUDO_PASSWORD" | tr -d '\\r\\n')"
            ssh -tt -o StrictHostKeyChecking=no -o BatchMode=yes -A ${sshUser}@${host} \
              "printf '%s\\n' \"\$CLEAN_SUDO_PASSWORD\" | sudo -k -S -p '' -v >/dev/null && sudo -n bash -s" <<'REMOTE_SCRIPT'
${remoteScript}
REMOTE_SCRIPT
        """
    }
}

def runUpdateOnHost = { host, sshUser, sakuraDockerSudoPasswordCredentialId, aptExpiredMetadataWorkaroundHosts ->
    echo "==== Updating ${host} ===="
    def useWorkaround = aptExpiredMetadataWorkaroundHosts.contains(host)
    def remoteScript = buildUpdateCommand(useWorkaround)

    if (ROOT_LOGIN_HOSTS.contains(host)) {
        runSshAsRoot(host, remoteScript)
        return
    }

    if (host == 'sakura-docker') {
        runSshWithPasswordSudo(host, sshUser, sakuraDockerSudoPasswordCredentialId, remoteScript)
        return
    }

    runSshWithSudo(host, sshUser, remoteScript)
}

pipeline {
    agent any

    triggers {
        cron('TZ=Asia/Tokyo\nH 3 * * *')
    }

    options {
        timestamps()
    }

    stages {
        stage('Update All machosts') {
            steps {
                script {
                    def failedHosts = []

                    for (host in TARGET_HOSTS) {
                        try {
                            runUpdateOnHost(
                                host,
                                SSH_USER,
                                SAKURA_DOCKER_SUDO_PASSWORD_CREDENTIAL_ID,
                                APT_EXPIRED_METADATA_WORKAROUND_HOSTS
                            )
                            echo "[OK] ${host}"
                        } catch (Exception e) {
                            echo "[ERROR] ${host}: ${e.getMessage()}"
                            failedHosts << host
                        }
                    }

                    if (!failedHosts.isEmpty()) {
                        error("Failed hosts: ${failedHosts.join(', ')}")
                    }
                }
            }
        }
    }

    post {
        success {
            echo 'All hosts updated successfully'
        }
        failure {
            echo 'Some hosts failed to update. Check logs.'
        }
    }
}