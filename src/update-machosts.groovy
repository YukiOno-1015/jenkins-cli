// Jenkins用: 複数machostサーバにsshで自動アップデートを定期実行
// サーバリストは下記配列で管理
// sakura-dockerのsudoパスワードのみJenkins Credentialsを利用

// サーバリスト（ホスト名またはIPアドレス）
def TARGET_HOSTS = [
    'nico',
    'umi',
    'nozomi',
    'maki',
    'eri',
    'tunnel01',
    'tunnel02',
    'sakura-docker',
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
    // 必要に応じて追加
]

def UPDATE_COMMAND = 'sudo apt-get clean && sudo rm -rf /var/lib/apt/lists/* && sudo apt-get update && sudo apt-get full-upgrade -y && sudo apt-get autoremove --purge -y'
def UPDATE_COMMAND_NO_SUDO = 'apt-get clean && rm -rf /var/lib/apt/lists/* && apt-get update && apt-get full-upgrade -y && apt-get autoremove --purge -y'
def SAKURA_DOCKER_SUDO_PASSWORD_CREDENTIAL_ID = 'sakura-docker-sudo-password'
def APT_EXPIRED_METADATA_WORKAROUND_HOSTS = TARGET_HOSTS

def SSH_USER = 'honoka' // サーバ側のユーザー名に合わせて変更

def runUpdateOnHost(host, sshUser, updateCommand, updateCommandNoSudo, sakuraDockerSudoPasswordCredentialId) {
    echo "==== Updating ${host} ===="
    def effectiveUpdateCommand = updateCommand
    def effectiveUpdateCommandNoSudo = updateCommandNoSudo
    if (APT_EXPIRED_METADATA_WORKAROUND_HOSTS.contains(host)) {
        effectiveUpdateCommand = updateCommand.replace('sudo apt-get update', 'sudo apt-get -o Acquire::Check-Valid-Until=false update')
        effectiveUpdateCommandNoSudo = updateCommandNoSudo.replace('apt-get update', 'apt-get -o Acquire::Check-Valid-Until=false update')
    }

    if (host == 'nico' || host == 'umi' || host == 'nozomi' || host == 'maki' || host == 'eri') {
        sh "ssh -o StrictHostKeyChecking=no -o BatchMode=yes -A root@${host} '${effectiveUpdateCommand}'"
        return
    }
    if (host == 'sakura-docker') {
        withCredentials([string(credentialsId: sakuraDockerSudoPasswordCredentialId, variable: 'SAKURA_DOCKER_SUDO_PASSWORD')]) {
            sh """
                set +x
                CLEAN_SUDO_PASSWORD="\$(printf '%s' "\$SAKURA_DOCKER_SUDO_PASSWORD" | tr -d '\\r\\n')"
                printf '%s\\n' "\$CLEAN_SUDO_PASSWORD" | ssh -tt -o StrictHostKeyChecking=no -o BatchMode=yes -A ${sshUser}@${host} "sudo -k -S -p '' bash -lc '${effectiveUpdateCommandNoSudo}'"
            """
        }
        return
    }
    sh "ssh -o StrictHostKeyChecking=no -o BatchMode=yes -A ${sshUser}@${host} '${effectiveUpdateCommand}'"
}

pipeline {
    agent any
    triggers { cron('H 3 * * *') } // 毎日3時に実行（必要に応じて変更）
    options { timestamps() }
    // パスフレーズ不要。ssh-agent/キーチェーン/.ssh/configに依存
    stages {
        stage('Update All machosts') {
            steps {
                script {
                    for (host in TARGET_HOSTS) {
                        try {
                            runUpdateOnHost(host, SSH_USER, UPDATE_COMMAND, UPDATE_COMMAND_NO_SUDO, SAKURA_DOCKER_SUDO_PASSWORD_CREDENTIAL_ID)
                        } catch (Exception e) {
                            echo "[ERROR] ${host}: ${e.getMessage()}"
                        }
                    }
                }
            }
        }
    }
    post {
        success { echo 'All hosts updated successfully' }
        failure { echo 'Some hosts failed to update. Check logs.' }
    }
}
