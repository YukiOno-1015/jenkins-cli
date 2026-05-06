# 統合GitHub Webhook Pipeline セットアップガイド

1つのWebhookエンドポイントで複数のGitHubリポジトリを自動判定してビルドするパイプラインです。

## 📋 概要

従来は各リポジトリごとに個別のパイプラインとWebhookを設定していましたが、この統合パイプラインでは:

✅ **1つのWebhookで全リポジトリに対応**  
✅ **リポジトリを自動判定**して適切なビルド処理を実行  
✅ **Jenkinsfileの有無で動作を自動分岐**

- Jenkinsfileがある → カスタムパイプライン実行
- Jenkinsfileがない → デフォルトでテスト+SonarQubeを実行
  ✅ **認証情報を自動選択**（`repositoryConfig.groovy`）  
  ✅ **リポジトリ別の設定を自動適用**（SonarQubeプロジェクト名など）

## 🚀 使用方法

### 🎯 推奨: Jenkinsfileなし（デフォルト動作）

**各リポジトリにJenkinsfileを配置する必要はありません。**

Webhookを受信すると、自動的に以下を実行:

- ✅ ユニットテスト実行
- ✅ SonarQube解析（設定されている場合）

**repositoryConfig.groovyに設定を追加するだけでOK！**

### 🔧 オプション: カスタムビルドが必要な場合

リポジトリ固有のビルド処理が必要な場合のみ、`Jenkinsfile`を配置:

**方法1: Shared Library版（推奨）**

```groovy
@Library('jqit-lib@main') _

unifiedWebhookPipeline()
```

**方法2: カスタムパイプライン**

```groovy
@Library('jqit-lib@main') _

pipeline {
  agent {
    kubernetes {
      yaml k8sPodYaml()
    }
  }

  stages {
    stage('Custom Build') {
      steps {
        sh 'your-custom-build-command'
      }
    }
  }
}
```

**方法3: 完全カスタム**

独自のパイプラインを定義（Jenkinsfile内で自由に記述）

## 🔧 Jenkins設定

### 1. Multibranch Pipelineジョブの作成

**重要**: 各リポジトリごとに1つのMultibranch Pipelineジョブを作成します。

#### Portal App用

1. **Jenkins Dashboard** → **New Item**
2. ジョブ名: `portal-app-unified-webhook`
3. タイプ: **Multibranch Pipeline**
4. **Branch Sources**:
   ```
   Git:
     - Project Repository: git@github.com:jqit-dev/Portal_App.git
     - Credentials: JQIT_ONO
     - Behaviors: Discover branches
   ```
5. **Build Configuration**:
   ```
   Mode: by Jenkinsfile
   Script Path: Jenkinsfile
   ```
6. **Scan Multibranch Pipeline Triggers**:
   ```
   ✅ Scan by webhook
   Trigger token: portal-app-webhook
   ```

#### Portal App Backend用

同様に作成し、以下を変更:

- ジョブ名: `portal-app-backend-unified-webhook`
- Project Repository: `git@github.com:jqit-dev/Portal_App_Backend.git`
- Trigger token: `portal-app-backend-webhook`

### 2. GitHub Webhookの設定

各リポジトリに対してWebhookを設定:

#### Portal App

GitHub → Settings → Webhooks → Add webhook:

```
Payload URL: https://jenkins.example.com/multibranch-webhook-trigger/invoke?token=portal-app-webhook
Content type: application/json
Secret: (オプション)
Events: Just the push event
```

#### Portal App Backend

```
Payload URL: https://jenkins.example.com/multibranch-webhook-trigger/invoke?token=portal-app-backend-webhook
Content type: application/json
Events: Just the push event
```

## 📁 リポジトリ構成

### パターンA: Jenkinsfileなし（推奨）

**デフォルトでテスト+SonarQubeを実行**

```
your-repo/
├── pom.xml
├── src/
└── ...
```

✅ Jenkinsfile不要  
✅ repositoryConfig.groovyに設定を追加するだけ

### パターンB: カスタムビルドが必要な場合

```
your-repo/
├── Jenkinsfile          ← カスタムビルドが必要な場合のみ配置
├── pom.xml
├── src/
└── ...
```

**Jenkinsfile の例**:

```groovy
@Library('jqit-lib@main') _

unifiedWebhookPipeline()
```

## ⚙️ リポジトリ設定の追加

新しいリポジトリを追加する場合は、`vars/repositoryConfig.groovy`を編集します。

### リポジトリ設定の追加

`vars/repositoryConfig.groovy`にリポジトリの全設定を追加:

```groovy
def configs = [
  'Portal_App': [
    // 認証情報
    credentialsId: 'JQIT_ONO',

    // ビルド設定
    buildProfiles: ['dev', 'prod'],
    defaultProfile: 'dev',

    // 成果物
    archivePattern: '**/target/portalApp-*.jar',

    // SonarQube
    sonarProjectName: 'Portal_App',
    sonarEnabled: true,

    // テスト
    skipTestsByDefault: false,

    // リソース要件（K8s）
    k8s: [
      image: 'nexus-docker.sk4869.info/honoka4869/jenkins-maven-node:latest',
      cpuRequest: '500m',
      memRequest: '2Gi',
      cpuLimit: '2',
      memLimit: '4Gi'
    ]
  ],

  'NewRepo': [  // ← 新しいリポジトリを追加
    credentialsId: 'NEW_CREDENTIALS_ID',
    buildProfiles: ['dev', 'staging', 'prod'],
    defaultProfile: 'dev',
    archivePattern: '**/target/*.jar',
    sonarProjectName: 'NewRepo',
    sonarEnabled: true,
    skipTestsByDefault: false,
    k8s: [
      image: 'nexus-docker.sk4869.info/honoka4869/jenkins-maven-node:latest',
      cpuRequest: '1',
      memRequest: '4Gi',
      cpuLimit: '4',
      memLimit: '8Gi'
    ]
  ]
]
```

## 🎯 現在サポートされているリポジトリ

| リポジトリ名       | 認証情報ID | アーカイブパターン              | SonarQubeプロジェクト |
| ------------------ | ---------- | ------------------------------- | --------------------- |
| Portal_App         | JQIT_ONO   | `**/target/portalApp-*.jar`     | Portal_App            |
| Portal_App_Backend | JQIT_ONO   | `**/target/portalApp-Api_*.jar` | Portal_App_Backend    |

## 📊 パイプラインのフロー

### パターンA: Jenkinsfileなし（デフォルト）

```
1. Repository Detection & Checkout
   ↓ リポジトリ名を自動判定
   ↓ 認証情報を自動選択
   ↓ リポジトリ設定を読み込み
   ↓ ソースコードをチェックアウト
   ↓ Jenkinsfileの存在を確認 → なし

2. Test
   ↓ ユニットテスト実行（mvn test）
   ↓ JUnitレポート収集

3. SonarQube Analysis（設定されている場合）
   ↓ コード品質分析
```

### パターンB: Jenkinsfileあり（カスタム）

```
1. Repository Detection & Checkout
   ↓ リポジトリ名を自動判定
   ↓ 認証情報を自動選択
   ↓ リポジトリ設定を読み込み
   ↓ ソースコードをチェックアウト
   ↓ Jenkinsfileの存在を確認 → あり！

2. Execute Custom Jenkinsfile
   ↓ リポジトリ内のJenkinsfileを実行
   ↓ カスタムビルド処理
```

## 🔍 動作確認

### Webhook配信のテスト

```bash
# リポジトリで変更をプッシュ
cd /path/to/Portal_App
git add .
git commit -m "Test unified webhook"
git push origin main
```

### Jenkinsでの確認

1. Jenkins Dashboard → 該当ジョブを開く
2. **Build History**に新しいビルドが自動的に追加される
3. コンソールログで以下を確認:

   **Jenkinsfileがない場合:**

   ```
   Repository Detection & Checkout
   Repository Name: Portal_App
   Using credentials: JQIT_ONO
   Jenkinsfile exists: false
   ========================================
   No Jenkinsfile - Running default tests
   Running Tests - Portal_App
   ```

   **Jenkinsfileがある場合:**

   ```
   Repository Detection & Checkout
   Repository Name: Portal_App
   Using credentials: JQIT_ONO
   Jenkinsfile exists: true
   ========================================
   Found Jenkinsfile - Executing custom pipeline
   ```

## 🐛 トラブルシューティング

### リポジトリが認識されない

**症状**:

```
⚠️  WARNING: No configuration found for repository: YourRepo
Using default configuration
```

**解決方法**:

1. `vars/unifiedWebhookPipeline.groovy`の`getRepositoryConfig()`にリポジトリを追加
2. リポジトリ名の大文字小文字が正確に一致しているか確認

### 認証エラーが発生する

**症状**:

```
⚠️  WARNING: No credentials mapping found for repository: YourRepo
```

**解決方法**:

1. `vars/resolveGitCredentials.groovy`にリポジトリのマッピングを追加
2. Jenkins Credentialsが正しく設定されているか確認

### Webhookが反応しない

**チェックリスト**:

- [ ] GitHub Webhookの配信履歴でHTTP 200が返っているか
- [ ] Jenkins側でMultibranch Pipeline Triggerが有効か
- [ ] Trigger tokenが正しいか
- [ ] JenkinsのURLがGitHubから到達可能か

## 🆚 従来の方法との比較

### 従来の方法（個別パイプライン）

```
Portal App
  → 専用パイプライン
  → 専用Webhook

Portal App Backend
  → 専用パイプライン
  → 専用Webhook
```

❌ リポジトリごとにパイプラインを作成・管理  
❌ Webhookエンドポイントが複数  
❌ 設定の重複

### 統合パイプライン（現在の方法）

```
1つのパイプラインテンプレート
  ↓
リポジトリ自動判定
  ↓
Jenkinsfileの有無を確認
  ↓
├─ なし → デフォルト（テスト+SonarQube）
└─ あり → カスタムパイプライン実行
```

✅ 1つのパイプラインテンプレートで全リポジトリに対応  
✅ **Jenkinsfile不要**（デフォルトでテスト+SonarQube実行）  
✅ カスタムビルドが必要な場合のみJenkinsfileを配置  
✅ 設定の一元管理（repositoryConfig.groovy）

## 📝 カスタマイズ例

### リソース要件の変更

```groovy
@Library('jqit-lib@main') _

unifiedWebhookPipeline(
  k8sCpuRequest: '1',
  k8sMemRequest: '4Gi',
  k8sCpuLimit: '4',
  k8sMemLimit: '8Gi'
)
```

### デフォルト動作の変更

```groovy
@Library('jqit-lib@main') _

unifiedWebhookPipeline(
  defaultBuildProfile: 'prod',
  defaultSkipTests: true,
  defaultRunSonarQube: false
)
```

## 📚 関連ファイル

- **[vars/core/repositoryConfig.groovy](../vars/core/repositoryConfig.groovy)** - リポジトリ設定の一元管理（認証情報、ビルド設定など）
- **[vars/core/unifiedWebhookPipeline.groovy](../vars/core/unifiedWebhookPipeline.groovy)** - Shared Library実装
- **[src/unifiedWebhookPipeline.groovy](../src/unifiedWebhookPipeline.groovy)** - 直接実装版
- **[vars/core/authenticatedCheckout.groovy](../vars/core/authenticatedCheckout.groovy)** - 認証付きチェックアウト
- **[vars/kubernetes/k8sPodYaml.groovy](../vars/kubernetes/k8sPodYaml.groovy)** - Kubernetes Pod設定

## 💡 ベストプラクティス

1. **まずはJenkinsfileなしで運用**: デフォルト動作（テスト+SonarQube）で十分な場合が多い
2. **カスタムビルドが必要な場合のみJenkinsfileを追加**: 必要最小限のカスタマイズに留める
3. **設定の一元管理**: 共通設定は`repositoryConfig.groovy`で管理
4. **段階的な導入**: まず1つのリポジトリで動作確認してから展開

---

**作成日**: 2026-01-17  
**更新日**: 2026-01-17
