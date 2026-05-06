# リポジトリ設定管理ガイド

全てのリポジトリに関する設定（認証情報、ビルド設定、SonarQube設定など）を一元管理する方法について説明します。

## 📁 構成ファイル

### `vars/repositoryConfig.groovy`

**役割**: リポジトリに関する全ての設定を一箇所で管理

- ✅ 認証情報ID
- ✅ ビルドプロファイル
- ✅ アーカイブパターン
- ✅ SonarQube設定
- ✅ テスト設定
- ✅ Kubernetes リソース要件

## 🎯 設定項目

### 基本構造

```groovy
'リポジトリ名': [
  // 認証情報
  credentialsId: 'Jenkins Credentials ID',

  // ビルド設定
  buildProfiles: ['dev', 'staging', 'prod'],
  defaultProfile: 'dev',

  // 成果物
  archivePattern: '**/target/*.jar',

  // SonarQube
  sonarProjectName: 'プロジェクト名',
  sonarEnabled: true,

  // テスト
  skipTestsByDefault: false,

  // Kubernetes
  k8s: [
    image: 'コンテナイメージ',
    cpuRequest: 'CPU要求量',
    memRequest: 'メモリ要求量',
    cpuLimit: 'CPU上限',
    memLimit: 'メモリ上限'
  ]
]
```

### 現在の設定例

```groovy
def configs = [
  'Portal_App': [
    credentialsId: 'JQIT_ONO',
    buildProfiles: ['dev', 'prod'],
    defaultProfile: 'dev',
    archivePattern: '**/target/portalApp-*.jar',
    sonarProjectName: 'Portal_App',
    sonarEnabled: true,
    skipTestsByDefault: false,
    k8s: [
      image: 'nexus-docker.sk4869.info/honoka4869/jenkins-maven-node:latest',
      cpuRequest: '500m',
      memRequest: '2Gi',
      cpuLimit: '2',
      memLimit: '4Gi'
    ]
  ],

  'Portal_App_Backend': [
    credentialsId: 'JQIT_ONO',
    buildProfiles: ['dev', 'local', 'prod'],
    defaultProfile: 'dev',
    archivePattern: '**/target/portalApp-Api_*.jar',
    sonarProjectName: 'Portal_App_Backend',
    sonarEnabled: true,
    skipTestsByDefault: false,
    k8s: [
      image: 'nexus-docker.sk4869.info/honoka4869/jenkins-maven-node:latest',
      cpuRequest: '500m',
      memRequest: '2Gi',
      cpuLimit: '2',
      memLimit: '4Gi'
    ]
  ]
]
```

## 🚀 使用方法

### 基本的な使い方

```groovy
// リポジトリ名から設定を取得
def config = repositoryConfig('Portal_App')

echo "Credentials: ${config.credentialsId}"
echo "Archive Pattern: ${config.archivePattern}"
echo "SonarQube Project: ${config.sonarProjectName}"
```

### URLから設定を取得

```groovy
// リポジトリURLから自動的にリポジトリ名を抽出して設定を取得
def config = repositoryConfig('git@github.com:jqit-dev/Portal_App.git')

echo "Repository Name: ${config.repoName}"
echo "Credentials: ${config.credentialsId}"
```

### 現在のコンテキストから取得

```groovy
// env.GIT_URLから自動的に設定を取得
def config = repositoryConfig.getCurrent()

echo "Current Repository: ${config.repoName}"
echo "Build Profiles: ${config.buildProfiles}"
```

### 特定の項目だけ取得

```groovy
// 認証情報IDだけ取得
def credId = repositoryConfig.getCredentialsId('Portal_App')

echo "Credentials ID: ${credId}"
```

### 全リポジトリの設定を取得

```groovy
// 全リポジトリの設定を一括取得
def allConfigs = repositoryConfig.getAll()

allConfigs.each { name, config ->
  echo "Repository: ${name}"
  echo "  Credentials: ${config.credentialsId}"
  echo "  Archive: ${config.archivePattern}"
}
```

## 📝 設定の追加

### 新しいリポジトリの追加

`vars/repositoryConfig.groovy`を編集:

```groovy
def configs = [
  'Portal_App': [
    // ... 既存の設定 ...
  ],

  // 新しいリポジトリを追加
  'NewProject': [
    credentialsId: 'NEW_CREDENTIALS_ID',
    buildProfiles: ['dev', 'staging', 'prod'],
    defaultProfile: 'dev',
    archivePattern: '**/build/libs/*.jar',
    sonarProjectName: 'NewProject',
    sonarEnabled: true,
    skipTestsByDefault: false,
    k8s: [
      image: 'gradle:jdk17',
      cpuRequest: '1',
      memRequest: '4Gi',
      cpuLimit: '4',
      memLimit: '8Gi'
    ]
  ]
]
```

### カスタム設定項目の追加

プロジェクト固有の設定を追加することも可能:

```groovy
'SpecialProject': [
  // 標準設定
  credentialsId: 'SPECIAL_CREDS',
  buildProfiles: ['dev', 'prod'],
  archivePattern: '**/target/*.jar',

  // カスタム設定
  deploymentConfig: [
    environment: 'production',
    namespace: 'special-ns',
    replicas: 3
  ],

  // 通知設定
  notification: [
    slackChannel: '#special-builds',
    emailList: ['team@example.com']
  ],

  // カスタムビルドステップ
  customSteps: [
    preBuild: ['./scripts/pre-build.sh'],
    postBuild: ['./scripts/post-build.sh']
  ]
]
```

## 🔄 パイプラインでの使用例

### 統合Webhookパイプラインでの使用

```groovy
@Library('jqit-lib@main') _

pipeline {
  agent { ... }

  stages {
    stage('Setup') {
      steps {
        script {
          // 設定を一括取得
          def checkoutInfo = authenticatedCheckout()
          def config = checkoutInfo.config

          // 設定を環境変数に保存
          env.ARCHIVE_PATTERN = config.archivePattern
          env.SONAR_PROJECT = config.sonarProjectName

          // ビルドプロファイルのバリデーション
          if (!config.buildProfiles.contains(params.BUILD_PROFILE)) {
            error("Invalid build profile: ${params.BUILD_PROFILE}")
          }
        }
      }
    }

    stage('Build') {
      steps {
        script {
          def config = repositoryConfig(env.REPO_NAME)

          sh """
            mvn clean package -P${params.BUILD_PROFILE}
          """
        }
      }
    }
  }
}
```

### カスタム設定の活用

```groovy
stage('Custom Deployment') {
  steps {
    script {
      def config = repositoryConfig(env.REPO_NAME)

      // カスタム設定があれば使用
      if (config.deploymentConfig) {
        echo "Deploying to: ${config.deploymentConfig.environment}"

        sh """
          kubectl apply -f deployment.yaml \
            --namespace=${config.deploymentConfig.namespace} \
            --replicas=${config.deploymentConfig.replicas}
        """
      }
    }
  }
}
```

## 🔍 デバッグ

### 設定内容の確認

```groovy
stage('Debug Config') {
  steps {
    script {
      def config = repositoryConfig('Portal_App')

      // 全設定を表示
      echo "=== Configuration for Portal_App ==="
      config.each { key, value ->
        echo "${key}: ${value}"
      }
    }
  }
}
```

### 設定の存在確認

```groovy
stage('Validate Config') {
  steps {
    script {
      def config = repositoryConfig(env.REPO_NAME)

      // 必須項目のチェック
      def required = ['credentialsId', 'archivePattern', 'sonarProjectName']
      required.each { key ->
        if (!config.containsKey(key)) {
          error("Missing required configuration: ${key}")
        }
      }

      echo "✅ Configuration validated"
    }
  }
}
```

## 📊 設定の比較

### リポジトリ間の設定差分

```groovy
stage('Compare Configs') {
  steps {
    script {
      def config1 = repositoryConfig('Portal_App')
      def config2 = repositoryConfig('Portal_App_Backend')

      echo "=== Differences ==="
      ['buildProfiles', 'archivePattern'].each { key ->
        if (config1[key] != config2[key]) {
          echo "${key}:"
          echo "  Portal_App: ${config1[key]}"
          echo "  Portal_App_Backend: ${config2[key]}"
        }
      }
    }
  }
}
```

## 🔒 ベストプラクティス

### 1. 設定のバージョン管理

`vars/repositoryConfig.groovy`はGitで管理されるため:

- ✅ 変更履歴が追跡される
- ✅ レビューが可能
- ✅ ロールバックが容易

### 2. 設定の階層化

```groovy
// デフォルト設定を定義
def defaultConfig = [
  credentialsId: 'JQIT_ONO',
  sonarEnabled: true,
  skipTestsByDefault: false,
  k8s: [
    image: 'nexus-docker.sk4869.info/honoka4869/jenkins-maven-node:latest',
    cpuRequest: '500m',
    memRequest: '2Gi',
    cpuLimit: '2',
    memLimit: '4Gi'
  ]
]

// 各リポジトリはデフォルトを継承してカスタマイズ
'Portal_App': defaultConfig + [
  buildProfiles: ['dev', 'prod'],
  archivePattern: '**/target/portalApp-*.jar',
  sonarProjectName: 'Portal_App'
]
```

### 3. 環境別の設定

```groovy
'Portal_App': [
  credentialsId: 'JQIT_ONO',

  // 環境別設定
  environments: [
    dev: [
      buildProfile: 'dev',
      deployTarget: 'dev-cluster'
    ],
    prod: [
      buildProfile: 'prod',
      deployTarget: 'prod-cluster',
      requiresApproval: true
    ]
  ]
]
```

## 📚 関連ファイル

- **[vars/repositoryConfig.groovy](vars/repositoryConfig.groovy)** - 設定定義ファイル
- **[vars/authenticatedCheckout.groovy](vars/authenticatedCheckout.groovy)** - 設定を使用するチェックアウト関数
- **[vars/unifiedWebhookPipeline.groovy](vars/unifiedWebhookPipeline.groovy)** - 設定を使用するパイプライン
- **[UNIFIED_WEBHOOK_SETUP.md](UNIFIED_WEBHOOK_SETUP.md)** - セットアップガイド

## 💡 トラブルシューティング

### 設定が見つからない

**症状**:

```
⚠️  WARNING: No configuration found for repository: YourRepo
Using default configuration
```

**解決方法**:

1. リポジトリ名を確認（大文字小文字を含む）
2. `vars/repositoryConfig.groovy`に設定を追加
3. Shared Libraryの更新を確認

### 設定の更新が反映されない

**原因**: Shared Libraryのキャッシュ

**解決方法**:

1. Jenkinsfileで`@Library('jqit-lib@main') _`を使用していることを確認
2. `main`ブランチに変更がプッシュされていることを確認
3. Jenkins側でShared Libraryの更新を確認

---

**作成日**: 2026-01-17
