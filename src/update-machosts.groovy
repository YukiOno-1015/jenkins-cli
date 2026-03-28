// Jenkins用: 複数machostサーバにsshで自動アップデートを定期実行
// サーバリストは下記配列で管理
// 秘密鍵・パスフレーズはJenkins Credentials (github-ssh-privatekey, github-ssh-passphrase) を利用
// agentはmasterまたはssh-agentが使えるノードで

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

def UPDATE_COMMAND = 'sudo apt update && sudo apt upgrade -y && sudo apt dist-upgrade -y && sudo apt full-upgrade -y && sudo apt autoremove --purge -y'

def SSH_USER = 'honoka' // サーバ側のユーザー名に合わせて変更

def runUpdateOnHost(host) {
    echo "==== Updating ${host} ===="
    // ssh-agent + 秘密鍵でコマンド実行
    sshagent(credentials: ['github-ssh-privatekey']) {
        sh "ssh -o StrictHostKeyChecking=no -o BatchMode=yes -A ${SSH_USER}@${host} '${UPDATE_COMMAND}'"
    }
}

pipeline {
    agent any
    triggers { cron('H 3 * * *') } // 毎日3時に実行（必要に応じて変更）
    options { timestamps() }
    environment {
        // パスフレーズが必要な場合は環境変数で渡す
        GITHUB_SSH_PASSPHRASE = credentials('github-ssh-passphrase')
    }
    stages {
        stage('Update All machosts') {
            steps {
                script {
                    for (host in TARGET_HOSTS) {
                        try {
                            runUpdateOnHost(host)
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
