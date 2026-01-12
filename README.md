# Jenkins Shared Library

Jenkins 用の共有ライブラリとパイプライン定義を提供するプロジェクトです。Kubernetes 環境での Maven+Node ビルドや、Cloudflare WAF の自動管理機能を含みます。

## 目次

- [機能](#機能)
- [プロジェクト構成](#プロジェクト構成)
- [前提条件](#前提条件)
- [セットアップ](#セットアップ)
  - [1. Shared Library として登録](#1-shared-library-として登録)
  - [2. Kubernetes 環境の準備](#2-kubernetes-環境の準備)
  - [3. 認証情報の登録](#3-認証情報の登録)
- [使用方法](#使用方法)
  - [gitCloneSsh](#gitclonessh)
  - [k8sPodYaml](#k8spodyaml)
  - [k8sMavenNodePipeline](#k8smavennodepipeline)
- [設定](#設定)
  - [Cloudflare Allowlist 自動更新](#cloudflare-allowlist-自動更新)
- [トラブルシューティング](#トラブルシューティング)
- [変更履歴](#変更履歴)

## 機能

### 1. Kubernetes 上での Maven+Node ビルドパイプライン

- Kubernetes Pod 上で Maven ビルドを実行
- SSH 経由での Git リポジトリクローン
- 複数の Maven プロファイル対応（dev, local, prod）
- ビルドアーティファクトの自動アーカイブ

### 2. Cloudflare IP Allowlist 自動更新

- Jenkins サーバーのグローバル IP を定期的に取得
- IP 変更時に自動的に Cloudflare WAF ルールを更新
- 前回 IP と現在 IP の両方を許可リストに保持

### 3. 再利用可能なヘルパー関数

- SSH 認証を使った Git クローン
- Kubernetes Pod 定義の動的生成

## プロジェクト構成

```
jenkins-cli/
├── README.md                                # このファイル
├── .gitignore                               # Git除外設定（Serena、Cloudflare state含む）
├── src/                                     # パイプライン定義
│   ├── portalAppPipeline.groovy            # Portal App Backend ビルドパイプライン
│   ├── portalAppBackEndPipeline.groovy     # Portal App Backend 代替パイプライン（異なる認証情報）
│   └── declarative-pipeline.groovy          # Cloudflare allowlist 更新パイプライン
├── vars/                                    # Jenkins Shared Library 関数
│   ├── gitCloneSsh.groovy                  # SSH経由でGitクローン（改善版）
│   ├── k8sMavenNodePipeline.groovy         # K8s上でMaven+Nodeビルド実行（汎用化）
│   └── k8sPodYaml.groovy                   # Kubernetes Pod定義生成（MAVEN_OPTS追加）
└── scripts/
    └── cf_update_jenkins_allowlist.sh      # Cloudflare IP allowlist更新スクリプト
```

## 前提条件

### Jenkins 環境

**必須バージョン:**

- Jenkins 2.x 以上（推奨: 2.400+）

**必須プラグイン:**

以下のプラグインがインストールされている必要があります：

| プラグイン名        | ID                  | 説明                           | 必須理由                           |
| ------------------- | ------------------- | ------------------------------ | ---------------------------------- |
| Pipeline            | workflow-aggregator | パイプライン機能の基本         | Declarative/Scripted Pipeline 実行 |
| Git                 | git                 | Git リポジトリとの連携         | ソースコード管理                   |
| SSH Agent           | ssh-agent           | SSH 認証の管理                 | GitHub SSH 接続                    |
| Kubernetes          | kubernetes          | Kubernetes 上でビルド実行      | K8s Pod エージェント起動           |
| Credentials Binding | credentials-binding | 認証情報のバインディング       | パイプラインでの認証情報使用       |
| Workspace Cleanup   | ws-cleanup          | ワークスペースのクリーンアップ | `cleanWs()` メソッド使用           |
| Timestamper         | timestamper         | ログにタイムスタンプ追加       | デバッグ用（オプション）           |

**プラグインのインストール方法:**

1. **Manage Jenkins** → **Manage Plugins** → **Available plugins**
2. 上記プラグインを検索してチェック
3. **Install without restart**（または**Download now and install after restart**）をクリック

または、Jenkins CLI でインストール：

```bash
# Jenkins CLI を使用したプラグインインストール
jenkins-cli install-plugin workflow-aggregator git ssh-agent kubernetes credentials-binding ws-cleanup timestamper
```

### Kubernetes 環境

- 利用可能な Kubernetes クラスター
- Jenkins からアクセス可能な Namespace
- Docker Hub へのアクセス（カスタムイメージ使用時）

### 認証情報

以下の認証情報を Jenkins に登録する必要があります：

| ID                        | 種別                          | 登録先     | 説明                       | 使用箇所                 |
| ------------------------- | ----------------------------- | ---------- | -------------------------- | ------------------------ |
| `github-ssh`              | SSH Username with private key | Jenkins    | GitHub SSH 認証鍵          | portalAppPipeline        |
| `JQIT_ONO`                | SSH Username with private key | Jenkins    | GitHub SSH 認証鍵（代替）  | portalAppBackEndPipeline |
| `dockerhub-jenkins-agent` | docker-registry Secret        | Kubernetes | Docker Hub imagePullSecret | k8sPodYaml               |
| `CF_API_TOKEN`            | Secret text                   | Jenkins    | Cloudflare API トークン    | declarative-pipeline     |
| `CF_ZONE_ID`              | Secret text                   | Jenkins    | Cloudflare ゾーン ID       | declarative-pipeline     |

## セットアップ

### 1. Shared Library として登録

Jenkins 管理画面で以下の設定を行います：

1. **Manage Jenkins** → **Configure System** → **Global Pipeline Libraries**
2. 以下の情報を入力：
   - **Name**: `jqit-lib`（任意の名前、パイプラインで`@Library`に使用）
   - **Default version**: `main`（使用するブランチ）
   - **Retrieval method**: **Modern SCM**
   - **Source Code Management**: **Git**
   - **Project Repository**: `https://github.com/YukiOno-1015/jenkins-cli.git`
   - **Credentials**: （プライベートリポジトリの場合のみ必要）
3. **Save**をクリック

### 2. Kubernetes 環境の準備

#### 2.1 Namespace の作成（未作成の場合）

```bash
kubectl create namespace jenkins
```

#### 2.2 Docker Hub imagePullSecret の作成

プライベートイメージを使用する場合のみ必要です：

```bash
# Docker Hub の認証情報を使ってKubernetesシークレットを作成
kubectl create secret docker-registry dockerhub-jenkins-agent \
  --docker-server=https://index.docker.io/v1/ \
  --docker-username=<your-dockerhub-username> \
  --docker-password=<your-dockerhub-password> \
  --docker-email=<your-email> \
  --namespace=jenkins

# 作成確認
kubectl get secret dockerhub-jenkins-agent -n jenkins
```

**注意**: パブリックイメージのみ使用する場合、この手順はスキップできます。

#### 2.3 Jenkins Kubernetes Cloud 設定

1. **Manage Jenkins** → **Configure System** → **Cloud**
2. **Add a new cloud** → **Kubernetes**
3. 以下を設定：
   - **Name**: `kubernetes`
   - **Kubernetes URL**: Kubernetes クラスタのエンドポイント（空欄で自動検出）
   - **Kubernetes Namespace**: `jenkins`
   - **Credentials**: Kubernetes への接続に必要な場合のみ
   - **Jenkins URL**: Jenkins 自身の URL（例: `http://jenkins-svc:8080`）
   - **Jenkins tunnel**: （オプション）JNLP 接続用
4. **Test Connection**で接続を確認
5. **Save**をクリック

### 3. 認証情報の登録

#### 3.1 GitHub SSH 認証鍵

```
Manage Jenkins → Manage Credentials → Add Credentials
- Kind: SSH Username with private key
- ID: github-ssh
- Username: git
- Private Key: （GitHub用の秘密鍵を入力）
```

**Docker Hub imagePullSecret:**

```bash
# 1. Docker Hub の認証情報を使ってKubernetesシークレットを作成
kubectl create secret docker-registry dockerhub-jenkins-agent \
  --docker-server=https://index.docker.io/v1/ \
  --docker-username=<your-dockerhub-username> \
  --docker-password=<your-dockerhub-password> \
  --docker-email=<your-email> \
  --namespace=jenkins

# 2. シークレットが作成されたことを確認
kubectl get secret dockerhub-jenkins-agent -n jenkins

# 注意: プライベートイメージを使用しない場合、この設定は不要です
# パブリックイメージのみ使用する場合は、k8sPodYamlのimagePullSecretパラメータを空文字列に設定できます
```

**Cloudflare 認証情報:**

```
Manage Jenkins → Manage Credentials → Add Credentials
- Kind: Secret text
- Secret: （各値を入力）
- ID: CF_API_TOKEN / CF_ZONE_ID
```

## 使用方法

### gitCloneSsh

SSH 認証を使って Git リポジトリをクローンします。

```groovy
gitCloneSsh(
    repoUrl: 'git@github.com:your-org/your-repo.git',
    branch: 'main',
    dir: 'repo',
    sshCredentialsId: 'github-ssh',
    knownHost: 'github.com'
)
```

**パラメータ:**

- `repoUrl` (必須): Git リポジトリの SSH URL
- `sshCredentialsId` (必須): SSH 認証情報 ID
- `branch` (オプション): クローンするブランチ（デフォルト: `main`）
- `dir` (オプション): クローン先ディレクトリ（デフォルト: `repo`）
- `knownHost` (オプション): known_hosts に追加するホスト（デフォルト: `github.com`）

**改善点 (2026-01-12):**

- ✅ エラーハンドリングの明確化（明示的な if 文）
- ✅ `set -euo pipefail` でパイプラインエラーも検出
- ✅ SSH ディレクトリのパーミッション設定（700）
- ✅ known_hosts の重複チェック（`-H` オプションでハッシュ化）
- ✅ shallow clone（`--depth 1 --single-branch`）でパフォーマンス向上
- ✅ 詳細なログメッセージ

### k8sPodYaml

Kubernetes Pod 定義 YAML を生成します。

```groovy
def podYaml = k8sPodYaml(
    image: 'honoka4869/jenkins-maven-node:latest',
    imagePullSecret: 'dockerhub-jenkins-agent',
    cpuRequest: '500m',
    memRequest: '2Gi',
    cpuLimit: '2',
    memLimit: '4Gi'
)
```

**パラメータ:**

- `image`: コンテナイメージ（デフォルト: `honoka4869/jenkins-maven-node:latest`）
- `imagePullSecret`: imagePullSecrets 名（デフォルト: `dockerhub-jenkins-agent`）
- `cpuRequest`: CPU 要求量（デフォルト: `500m`）
- `memRequest`: メモリ要求量（デフォルト: `2Gi`）
- `cpuLimit`: CPU 制限量（デフォルト: `2`）
- `memLimit`: メモリ制限量（デフォルト: `4Gi`）

**改善点 (2026-01-12):**

- ✅ YAML 形式の標準化（`---` で開始）
- ✅ `MAVEN_OPTS` 環境変数の追加（メモリ最適化）
- ✅ `restartPolicy: Never` の明示的設定
- ✅ インデントの統一

### k8sMavenNodePipeline

Kubernetes 上で Maven ビルドを実行する完全なパイプラインです。

```groovy
@Library('jqit-lib@main') _

k8sMavenNodePipeline(
    gitRepoUrl: 'git@github.com:your-org/your-repo.git',
    gitBranch: 'main',
    gitSshCredentialsId: 'github-ssh',
    mavenProfileChoices: ['dev', 'staging', 'prod'],
    mavenDefaultProfile: 'dev',
    archivePattern: '**/target/*.jar'
)
```

**パラメータ:**

- `gitRepoUrl` (必須): Git リポジトリの SSH URL
- `gitBranch`: デフォルトブランチ（デフォルト: `main`）
- `gitSshCredentialsId`: SSH 認証情報 ID（デフォルト: `github-ssh`）
- `mavenProfileChoices`: 選択可能な Maven プロファイルリスト
- `mavenDefaultProfile`: デフォルトプロファイル（デフォルト: `dev`）
- `mavenCommand`: Maven 実行コマンド（デフォルト: `mvn -B clean package`）
- `archivePattern`: アーカイブするファイルパターン（デフォルト: `**/target/*.jar`）
- `image`: ビルド用コンテナイメージ
- `cpuRequest`, `memRequest`, `cpuLimit`, `memLimit`: リソース設定

**改善点 (2026-01-12):**

- ✅ ハードコードされたリポジトリ URL とブランチを削除（再利用性向上）
- ✅ `gitRepoUrl` を必須パラメータに変更
- ✅ `archivePattern` を汎用的なパターンに変更
- ✅ ワークスペースのクリーンアップ処理を追加（cleanup ポストアクション）
- ✅ タイムスタンプ出力を追加（`timestamps()` オプション）
- ✅ `dir('repo')` を使用してコードを簡潔化
- ✅ `allowEmptyArchive: false` でアーカイブの検証を強化

## 設定

### Cloudflare Allowlist 自動更新

`src/declarative-pipeline.groovy`を使用して、定期的に Jenkins サーバーの IP を Cloudflare WAF に反映します。

**設定手順:**

1. Cloudflare API トークンとゾーン ID を取得
2. Jenkins 認証情報に登録
3. パイプラインジョブを作成し、`src/declarative-pipeline.groovy`を指定
4. ビルドトリガーは自動設定されています（10 分ごと）

**環境変数:**

- `HOSTNAME`: 保護対象のホスト名（デフォルト: `jenkins-svc.sk4869.info`）
- `RULE_DESC`: Cloudflare ルールの説明（デフォルト: `allowlist-jenkins-svc`）
- `IP_SOURCE_URL`: IP 取得元 URL（デフォルト: `https://ifconfig.me`）
- `SCRIPT_PATH`: スクリプトパス（`${WORKSPACE}/scripts/cf_update_jenkins_allowlist.sh`）

**改善点 (2026-01-12):**

- ✅ ハードコードされた絶対パス（`/Volumes/HDD/...`）を削除
- ✅ `${WORKSPACE}` を使用した環境非依存化
- ✅ スクリプト存在確認の追加
- ✅ エラーメッセージの改善
- ✅ success ポストアクションの追加

### カスタムビルドイメージ

デフォルトでは`honoka4869/jenkins-maven-node:latest`を使用しますが、独自のイメージも使用できます：

```groovy
k8sMavenNodePipeline(
    gitRepoUrl: 'git@github.com:your-org/your-repo.git',
    image: 'your-registry/your-image:tag',
    imagePullSecret: 'your-pull-secret'
)
```

## トラブルシューティング

### cleanWs メソッドが見つからないエラー

```
java.lang.NoSuchMethodError: No such DSL method 'cleanWs' found
```

**原因**: Workspace Cleanup Plugin がインストールされていません。

**解決方法**:

1. **Manage Jenkins** → **Manage Plugins** → **Available plugins**
2. `Workspace Cleanup` を検索してインストール
3. Jenkins を再起動

または、Jenkins CLI で：

```bash
jenkins-cli install-plugin ws-cleanup
```

**代替手段**: プラグインをインストールしたくない場合、[vars/k8sMavenNodePipeline.groovy](vars/k8sMavenNodePipeline.groovy)の`cleanup`セクションをコメントアウトするか、`deleteDir()`に置き換えてください。

### SSH 接続エラー

```
Host key verification failed
```

→ `gitCloneSsh`の`knownHost`パラメータが正しく設定されているか確認してください。

### Kubernetes Pod 起動エラー

```
Failed to create pod
```

→ imagePullSecret が正しく設定されているか、Namespace のリソースクォータを確認してください。

### Maven OOM (Out of Memory)

→ `k8sPodYaml`の`memLimit`を増やすか、`MAVEN_OPTS`を調整してください。

## ライセンス

このプロジェクトは内部使用を目的としています。

## 貢献

バグ報告や機能要望は、Issue を作成してください。

## 変更履歴

### 2026-01-12 - 大規模リファクタリング

#### セキュリティ向上

- SSH known_hosts の適切な管理とパーミッション設定
- エラーハンドリングの強化（`set -euo pipefail`）
- 認証情報の明確化

#### 移植性向上

- 環境依存のハードコードパス削除
- `${WORKSPACE}` を使用した相対パス化
- プロジェクト固有設定の削除

#### 保守性向上

- `Constants.groovy` → `portalAppPipeline.groovy` へリネーム
- 不要なディレクトリ階層（`jp/co/jqit/jenkins/`）の削除
- 詳細なコメントとドキュメント追加
- README.md の作成
- .gitignore の最適化

#### 再利用性向上

- 汎用的なパラメータ設定
- ハードコードされたデフォルト値の削除
- 必須パラメータの明確化

#### パフォーマンス向上

- shallow clone（`--depth 1 --single-branch`）
- リソースの適切なクリーンアップ
- MAVEN_OPTS の最適化

#### デバッグ性向上

- 詳細なログメッセージ
- タイムスタンプの追加
- エラー時の明確なメッセージ

## 作成者

このプロジェクトは JQIT チームによって開発・保守されています。
