/*
 * Portal App Backend 向けのビルドパイプラインです。
 *
 * ここでは対象リポジトリとブランチ指定だけを薄く定義し、
 * 認証情報・Maven/SonarQube 設定・Kubernetes Pod 設定などの詳細は
 * Shared Library と `vars/repositoryConfig.groovy` に集約しています。
 *
 * 運用メモ:
 * - 現在の変更ブランチと同名の Shared Library を優先して読み込みます。
 * - 読み込み失敗時は `main` にフォールバックして継続します。
 */

def libBranch = env.CHANGE_BRANCH ?: env.BRANCH_NAME ?: 'main'
def libId = "jqit-lib@${libBranch}"

try {
  library libId
} catch (err) {
  echo "${libId} の読み込みに失敗しました。jqit-lib@main へフォールバックします。"
  library 'jqit-lib@main'
}

// パラメータ定義（スクリプトパイプライン用）
properties([
  parameters([
    string(
      name: 'gitBranch',
      defaultValue: 'release1.0.0',
      description: 'Git branch to build (default: release1.0.0)'
    )
  ])
])

// 実ビルド本体は Shared Library に委譲し、このファイルは
// 「Backend をどのブランチでビルドするか」と「必要時にどこへ配備するか」を
// 薄く宣言するエントリポイントとして扱う。
//
// 補足:
// - `k8sMavenNodePipeline()` 自体が 1 本の Declarative Pipeline を生成するため、
//   Backend 側も `Remote Deploy` ステージをオプションで内包する構成に寄せる。
// - ただし Backend の本番反映方法は Portal App 本体と異なる可能性があるため、
//   下記 `deployCommand` は叩き台として置き、service 名や配置先は必要に応じて調整する。
k8sMavenNodePipeline(
  gitRepoUrl: 'git@github.com:jqit-dev/Portal_App_Backend.git',
  gitBranch: params.gitBranch ?: 'release1.0.0',

  // Backend の成果物は API 用 JAR を想定する。
  archivePattern: '**/target/portalApp-Api_*.jar',

  // `enableRemoteDeploy=true` で成果物転送を有効化し、
  // `runDeployCommand=true` のときだけ下記コマンドで配置反映まで実行する。
  enableRemoteDeploy: false,
  runDeployCommand: false,
  deployArtifactPattern: '**/target/portalApp-Api_*.jar',

  // 接続先候補と既定ホストは `repositoryConfig.groovy` 側へ寄せて共通管理する。
  // ここでは Backend 固有の配置先と deployCommand だけを持つ。 
  // `/opt/backend-api` は最終配置先、scp 転送自体は `/tmp/backend-api` に staging する。
  deployTargetDir: '/opt/backend-api',
  deployUploadDir: '/tmp/backend-api',
  deployUseSudo: true,
  deployCommand: '''
set -euo pipefail

# `DEPLOY_FIRST_ARTIFACT` は、SSH 転送後の staging 側フルパスです。
artifact_path="$DEPLOY_FIRST_ARTIFACT"
artifact_name="$(basename "$artifact_path")"
release_dir="$DEPLOY_TARGET_DIR"
release_path="$release_dir/$artifact_name"
release_link="$release_dir/portalApp-Api.jar"
backup_suffix="$(date +%Y%m%d%H%M%S)"

echo "=== Backend デプロイ開始 ==="
echo "成果物パス: $artifact_path"
echo "成果物ファイル名: $artifact_name"
echo "リリースディレクトリ: $release_dir"
echo "リリース先ファイル: $release_path"
echo "シンボリックリンク: $release_link"

sudo mkdir -p "$release_dir"
sudo chown "$USER":"$USER" -R "$release_dir"

if [ ! -f "$artifact_path" ]; then
  echo "ERROR: ステージング成果物が見つかりません: $artifact_path"
  exit 1
fi

# 既存の旧版 JAR は、今回リリースするものを除いて退避しておく。
find "$release_dir" -maxdepth 1 -type f -name 'portalApp-Api*.jar' ! -name "$artifact_name" -print | while read -r old_jar; do
  echo "旧版JARをバックアップ: $old_jar -> $old_jar.bak_${backup_suffix}"
  sudo mv "$old_jar" "$old_jar.bak_${backup_suffix}"
done

# 同名ファイルが既にある場合も上書き前にバックアップする。
if [ -f "$release_path" ]; then
  echo "同名JARをバックアップ: $release_path -> $release_path.bak_${backup_suffix}"
  sudo mv "$release_path" "$release_path.bak_${backup_suffix}"
fi

echo "新しいJARを配置: $artifact_path -> $release_path"
sudo install -m 0644 "$artifact_path" "$release_path"

echo "シンボリックリンクを切り替え: $release_link -> $release_path"
sudo rm -f "$release_link"
sudo ln -s "$release_path" "$release_link"

echo "サービス状態確認(停止前): backend"
sudo systemctl status backend --no-pager || true

echo "サービス停止: backend"
sudo systemctl stop backend

echo "サービス状態確認(停止後): backend"
sudo systemctl status backend --no-pager || true

echo "サービス起動: backend"
sudo systemctl start backend

echo "サービス状態確認(起動後): backend"
sudo systemctl status backend --no-pager || true

echo "ステージング成果物: $DEPLOY_FIRST_ARTIFACT"
echo "リリース成果物: $release_path"
echo "=== Backend デプロイ完了 ==="
''',

  // 以下の設定はrepositoryConfig.groovyから自動取得されます:
  // - gitSshCredentialsId
  // - mavenProfileChoices
  // - mavenDefaultProfile
  // - enableSonarQube
  // - sonarProjectName
  // - k8s リソース設定
)