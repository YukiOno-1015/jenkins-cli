# Jenkins Shared Library

Jenkins 用の共有ライブラリとパイプライン定義を提供するプロジェクトです。Kubernetes 環境での Maven+Node ビルドや、Cloudflare WAF の自動管理機能を含みます。

## 目次

- [機能](#機能)
- [プロジェクト構成](#プロジェクト構成)
- [前提条件](#前提条件)
- [セットアップ](#セットアップ)
- [使用方法](#使用方法)
- [設定](#設定)

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

- Jenkins 2.x 以上
- Kubernetes Plugin がインストール済み
- SSH Agent Plugin がインストール済み

### Kubernetes 環境

- 利用可能な Kubernetes クラスター
- Jenkins からアクセス可能な Namespace
- Docker Hub へのアクセス（カスタムイメージ使用時）

### 認証情報

以下の認証情報を Jenkins に登録する必要があります：

| ID                        | 種別                          | 説明                       | 使用箇所                 |
| ------------------------- | ----------------------------- | -------------------------- | ------------------------ |
| `github-ssh`              | SSH Username with private key | GitHub SSH 認証鍵          | portalAppPipeline        |
| `JQIT_ONO`                | SSH Username with private key | GitHub SSH 認証鍵（代替）  | portalAppBackEndPipeline |
| `dockerhub-jenkins-agent` | Secret file                   | Docker Hub imagePullSecret | k8sPodYaml               |
| `CF_API_TOKEN`            | Secret text                   | Cloudflare API トークン    | declarative-pipeline     |
| `CF_ZONE_ID`              | Secret text                   | Cloudflare ゾーン ID       | declarative-pipeline     |

## セットアップ

### 1. Shared Library として登録

Jenkins 管理画面で以下の設定を行います：

1. **Manage Jenkins** → **Configure System** → **Global Pipeline Libraries**
2. 以下の情報を入力：
   - Name: `jqit-lib`（任意の名前）
   - Default version: `main`
   - Retrieval method: **Modern SCM**
   - Source Code Management: **Git**
   - Project Repository: このリポジトリの URL

### 2. Kubernetes Cloud 設定

1. **Manage Jenkins** → **Configure System** → **Cloud**
2. **Add a new cloud** → **Kubernetes**
3. Kubernetes 接続情報を設定

### 3. 認証情報の登録

**GitHub SSH 認証鍵:**

```
Manage Jenkins → Manage Credentials → Add Credentials
- Kind: SSH Username with private key
- ID: github-ssh
- Username: git
- Private Key: （GitHub用の秘密鍵を入力）
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
