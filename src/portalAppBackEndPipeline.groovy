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
  echo "Failed to load ${libId}, falling back to jqit-lib@main"
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
  deployHost: '35.160.162.206',
  deployUser: 'ec2-user',
  deploySshCredentialsId: 'github-ssh',
  deployKnownHost: '35.160.162.206',
  // `/opt/backend-api` は最終配置先、scp 転送自体は `/tmp/backend-api` に staging する。
  deployTargetDir: '/opt/backend-api',
  deployUploadDir: '/tmp/backend-api',
  deployUseSudo: true,
  deployCommand: '''
set -euo pipefail

# Backend 側は本体アプリと反映方法が異なる可能性があるため、
# service 名・配置先・再起動手順は実環境に合わせて調整してください。
artifact_path="$DEPLOY_FIRST_ARTIFACT"
artifact_name="$(basename "$artifact_path")"
release_dir="$DEPLOY_TARGET_DIR"
release_path="$release_dir/$artifact_name"
release_link="$release_dir/portalApp-Api.jar"
backup_suffix="$(date +%Y%m%d%H%M%S)"

sudo mkdir -p "$release_dir"
sudo chown "$USER":"$USER" -R "$release_dir"

# 旧版 JAR は必要に応じてバックアップ退避する。
find "$release_dir" -maxdepth 1 -type f -name 'portalApp-Api.jar' ! -name "$artifact_name" -print | while read -r old_jar; do
  sudo mv "$old_jar" "$old_jar.bak_${backup_suffix}"
done

sudo install -m 0644 "$artifact_path" "$release_path"
sudo rm -f "$release_link"
sudo ln -s "$release_path" "$release_link"

# 例: systemd 管理の場合。必要なら service 名や起動方法を変更してください。
sudo systemctl restart portal-app-backend

echo "Staged artifact: $DEPLOY_FIRST_ARTIFACT"
echo "Released artifact: $release_path"
''',

  // 以下の設定はrepositoryConfig.groovyから自動取得されます:
  // - gitSshCredentialsId
  // - mavenProfileChoices
  // - mavenDefaultProfile
  // - enableSonarQube
  // - sonarProjectName
  // - k8s リソース設定
)