# 認証管理ガイド

GitHub Webhookパイプラインの認証ロジックについて説明します。

## 📁 認証関連ファイル

### 1. `vars/resolveGitCredentials.groovy`

**役割**: リポジトリURLから適切なJenkins Credentials IDを解決

```groovy
// 使用例
def credentialsId = resolveGitCredentials('git@github.com:jqit-dev/Portal_App.git')
// => 'JQIT_ONO'

// リポジトリ名のみを抽出
def repoName = resolveGitCredentials.extractRepoName('git@github.com:jqit-dev/Portal_App.git')
// => 'Portal_App'
```

**設定方法**:

```groovy
def credentialsMap = [
  'Portal_App': 'JQIT_ONO',
  'Portal_App_Backend': 'JQIT_ONO',
  'NewRepo': 'NEW_CREDENTIALS_ID',  // ← 新しいリポジトリを追加
]
```

### 2. `vars/authenticatedCheckout.groovy`

**役割**: 認証情報を自動選択してGit操作を実行

```groovy
// 基本的な使用方法（現在のGIT_URLから自動判定）
def info = authenticatedCheckout()

// カスタマイズ
def info = authenticatedCheckout(
  repoUrl: 'git@github.com:jqit-dev/Portal_App.git',
  branch: 'develop',
  dir: 'source',
  useScm: false  // gitCloneSshを使う場合
)

// 戻り値
// info = [
//   repoName: 'Portal_App',
//   credentialsId: 'JQIT_ONO',
//   branch: 'main'
// ]
```

### 3. `vars/gitCloneSsh.groovy`

**役割**: SSH認証でGitリポジトリをクローン（既存）

```groovy
gitCloneSsh(
  repoUrl: 'git@github.com:jqit-dev/Portal_App.git',
  branch: 'main',
  sshCredentialsId: 'JQIT_ONO',
  dir: 'repo'
)
```

### 4. `vars/remoteSsh.groovy`

**役割**: SSH認証で任意コマンドやスクリプトを安全に実行

```groovy
def osInfo = remoteSsh(
  host: 'machost',
  user: 'yukiono',
  sshCredentialsId: 'MACHOST_SSH',
  knownHost: 'machost',
  command: '''
if command -v sw_vers >/dev/null 2>&1; then
  sw_vers
elif [ -f /etc/os-release ]; then
  cat /etc/os-release
else
  uname -a
fi
''',
  returnStdout: true
)

echo osInfo
```

## 🔄 認証フロー

### Multibranch Pipeline（推奨）

```
GitHub Webhook
  ↓
Jenkins Multibranch Pipeline
  ↓
authenticatedCheckout()
  ↓ 内部で呼び出し
  ↓
resolveGitCredentials(env.GIT_URL)
  ↓ リポジトリ名を抽出
  ↓ 認証情報マッピングを参照
  ↓
env.GIT_CREDENTIALS_ID = 'JQIT_ONO'
env.REPO_NAME = 'Portal_App'
  ↓
checkout scm  ← Jenkins内部で自動的に認証情報を使用
```

### 明示的なクローン方式

```
authenticatedCheckout(useScm: false)
  ↓
resolveGitCredentials(repoUrl)
  ↓
gitCloneSsh(
  repoUrl: repoUrl,
  sshCredentialsId: credentialsId,
  ...
)
```

## 🎯 使用例

### 例1: 統合Webhookパイプライン

```groovy
@Library('jqit-lib@main') _

pipeline {
  agent { ... }

  stages {
    stage('Repository Detection & Checkout') {
      steps {
        script {
          // 認証情報を自動解決してチェックアウト
          def checkoutInfo = authenticatedCheckout()

          echo "Repository: ${checkoutInfo.repoName}"
          echo "Using credentials: ${checkoutInfo.credentialsId}"
          echo "Branch: ${checkoutInfo.branch}"
        }
      }
    }
  }
}
```

### 例2: 認証情報のみ取得

```groovy
stage('Get Credentials') {
  steps {
    script {
      // チェックアウトせずに認証情報だけ取得
      def auth = authenticatedCheckout.getCredentials()

      echo "Repository: ${auth.repoName}"
      echo "Credentials ID: ${auth.credentialsId}"

      // 後で使用
      env.GIT_CREDS = auth.credentialsId
    }
  }
}
```

### 例3: 複数リポジトリのクローン

```groovy
stage('Clone Multiple Repos') {
  steps {
    script {
      // メインリポジトリ
      authenticatedCheckout(dir: 'main')

      // 依存リポジトリ（異なる認証情報）
      def libAuth = authenticatedCheckout.getCredentials(
        'git@github.com:another-org/library.git'
      )

      gitCloneSsh(
        repoUrl: 'git@github.com:another-org/library.git',
        branch: 'main',
        sshCredentialsId: libAuth.credentialsId,
        dir: 'lib'
      )
    }
  }
}
```

### 例4: SSHでOSバージョンを確認

```groovy
stage('Check Remote OS Version') {
  steps {
    script {
      def osInfo = remoteSsh(
        host: 'machost',
        user: 'yukiono',
        sshCredentialsId: 'MACHOST_SSH',
        knownHost: 'machost',
        command: '''
if command -v sw_vers >/dev/null 2>&1; then
  sw_vers
elif [ -f /etc/os-release ]; then
  cat /etc/os-release
else
  uname -a
fi
''',
        returnStdout: true
      )

      echo "Remote OS information:\n${osInfo}"
    }
  }
}
```

### 例5: SSHで任意スクリプトを実行

```groovy
stage('Run Remote Deploy Script') {
  steps {
    script {
      remoteSsh(
        host: 'app-host01',
        user: 'deployer',
        sshCredentialsId: 'APP_HOST_SSH',
        knownHost: 'app-host01',
        script: '''
cd /srv/myapp
git fetch --all
git reset --hard origin/main
docker compose up -d --build
''',
        useSudo: false
      )
    }
  }
}
```

## 🔧 カスタマイズ

### 新しい認証方式の追加

`vars/resolveGitCredentials.groovy`を拡張:

```groovy
def call(String repoUrl) {
  def repoName = extractRepoName(repoUrl)

  // 組織ごとに認証情報を変える
  def org = extractOrgName(repoUrl)

  def orgCredentialsMap = [
    'jqit-dev': 'JQIT_ONO',
    'another-org': 'ANOTHER_CREDS',
  ]

  def credentialsId = orgCredentialsMap.get(org)

  if (!credentialsId) {
    // リポジトリ名で検索
    credentialsId = repoCredentialsMap.get(repoName)
  }

  return credentialsId ?: 'DEFAULT_CREDS'
}

def extractOrgName(String repoUrl) {
  // git@github.com:org/repo.git -> org
  if (repoUrl.contains('git@github.com:')) {
    return repoUrl.replaceAll('git@github.com:', '')
                  .replaceAll('.git$', '')
                  .split('/')[0]
  }
  return 'unknown'
}
```

### 動的な認証情報選択

```groovy
def call(String repoUrl) {
  def repoName = extractRepoName(repoUrl)

  // Jenkinsの環境変数から取得
  def credentialsId = env."CREDS_${repoName.toUpperCase()}"

  if (!credentialsId) {
    // デフォルトマッピング
    credentialsId = credentialsMap.get(repoName)
  }

  return credentialsId ?: 'DEFAULT_CREDS'
}
```

## 🔒 セキュリティベストプラクティス

### 1. Credentials IDの命名規則

```
形式: [SERVICE]_[USER]_[PURPOSE]
例:
  - GITHUB_JQIT_ONO
  - GITLAB_DEPLOY_KEY
  - BITBUCKET_READONLY
```

### 2. 最小権限の原則

```groovy
def credentialsMap = [
  // 読み取り専用アクセス
  'public-repo': 'GITHUB_READONLY',

  // ビルド用（読み取り + タグ作成）
  'app-repo': 'GITHUB_BUILD',

  // デプロイ用（読み取り + プッシュ）
  'deploy-repo': 'GITHUB_DEPLOY',
]
```

### 3. 認証情報のローテーション

定期的に認証情報を更新:

1. Jenkins Credentialsで新しいSSHキーを追加
2. GitHubに新しい公開鍵を登録
3. `resolveGitCredentials.groovy`を更新
4. 古い認証情報を削除

## 📊 トラブルシューティング

### 認証エラー: "Permission denied"

**原因**: 認証情報が見つからない、または権限不足

**確認事項**:

1. Jenkins Credentialsが存在するか
2. `resolveGitCredentials.groovy`にマッピングがあるか
3. GitHubにSSH公開鍵が登録されているか
4. リポジトリへのアクセス権があるか

**デバッグ**:

```groovy
stage('Debug Auth') {
  steps {
    script {
      def repoUrl = env.GIT_URL
      def repoName = resolveGitCredentials.extractRepoName(repoUrl)
      def credId = resolveGitCredentials(repoUrl)

      echo "Repository URL: ${repoUrl}"
      echo "Repository Name: ${repoName}"
      echo "Credentials ID: ${credId}"

      // Jenkins Credentialsの確認
      withCredentials([sshUserPrivateKey(
        credentialsId: credId,
        keyFileVariable: 'SSH_KEY'
      )]) {
        sh 'ssh-keygen -l -f $SSH_KEY'
      }
    }
  }
}
```

### 認証情報が見つからない

**症状**:

```
⚠️  WARNING: No credentials mapping found for repository: YourRepo
Using default credentials: JQIT_ONO
```

**解決方法**:

1. リポジトリ名を確認（大文字小文字を含む）
2. `vars/resolveGitCredentials.groovy`にマッピングを追加
3. Jenkins Credentialsを確認

## 📚 関連ファイル

- **[vars/resolveGitCredentials.groovy](vars/resolveGitCredentials.groovy)** - 認証情報解決ロジック
- **[vars/authenticatedCheckout.groovy](vars/authenticatedCheckout.groovy)** - 認証付きチェックアウト
- **[vars/gitCloneSsh.groovy](vars/gitCloneSsh.groovy)** - SSHクローン実装
- **[vars/unifiedWebhookPipeline.groovy](vars/unifiedWebhookPipeline.groovy)** - 統合パイプライン

---

**作成日**: 2026-01-17
