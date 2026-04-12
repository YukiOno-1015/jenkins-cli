/*
 * Portal App 向けのビルドパイプラインです。
 *
 * このファイルでは `k8sMavenNodePipeline` を呼び出す最小構成だけを定義し、
 * 実際のビルド条件・認証情報・Kubernetes リソース設定は
 * `vars/repositoryConfig.groovy` と Shared Library 側へ委譲しています。
 *
 * 運用メモ:
 * - PR / ブランチビルドでは現在のブランチ名に追従した Shared Library を優先して読み込みます。
 * - ライブラリ読み込みに失敗した場合でも `main` へ自動フォールバックし、運用停止を避けます。
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

// 実際のビルド処理は Shared Library に集約し、このファイルでは
// 対象リポジトリ URL と、必要最小限の上書きパラメータのみを指定する。
//
// 補足:
// - `k8sMavenNodePipeline()` 自体が 1 本の Declarative Pipeline を生成するため、
//   後ろに別の `remoteSshPipeline()` を並べる形では連結できない。
// - そのため、JAR 転送 / リモートデプロイは `k8sMavenNodePipeline` の
//   オプション引数として同一パイプライン内へ組み込む。
k8sMavenNodePipeline(
  gitRepoUrl: 'git@github.com:jqit-dev/Portal_App.git',
  gitBranch: params.gitBranch ?: 'release1.0.0',

  // Portal App は JAR 配布を想定しているため、成果物対象も JAR に合わせる。
  archivePattern: '**/target/*.jar',

  // `enableRemoteDeploy=true` で JAR を転送し、
  // `runDeployCommand=true` のときだけ配置反映や再起動まで実行する。
  enableRemoteDeploy: false,
  runDeployCommand: false,
  deployArtifactPattern: '**/target/*.jar',

  // 接続先候補と既定ホストは `repositoryConfig.groovy` 側へ寄せて共通管理する。
  // ここではアプリ固有の配置先と deployCommand だけを持つ。 
  // `/opt/portal-app` は最終配置先、scp 転送自体は `/tmp/portal-app` へ staging する。
  deployTargetDir: '/opt/portal-app',
  deployUploadDir: '/tmp/portal-app',
  deployUseSudo: true,
  deployCommand: '''
set -euo pipefail

# `DEPLOY_FIRST_ARTIFACT` は、SSH 転送後の staging 側フルパスです。
artifact_path="$DEPLOY_FIRST_ARTIFACT"
artifact_name="$(basename "$artifact_path")"
release_dir="$DEPLOY_TARGET_DIR"
release_path="$release_dir/$artifact_name"
release_link="$release_dir/portalApp.jar"
backup_suffix="$(date +%Y%m%d%H%M%S)"

sudo mkdir -p "$release_dir"

sudo chown "$USER":"$USER" -R "$release_dir"


# 既存の旧版 JAR は、今回リリースするものを除いて退避しておく。
find "$release_dir" -maxdepth 1 -type f -name 'portalApp-*.jar' ! -name "$artifact_name" -print | while read -r old_jar; do
  sudo mv "$old_jar" "$old_jar.bak_${backup_suffix}"
done

sudo install -m 0644 "$artifact_path" "$release_path"
sudo rm -f "$release_link"
sudo ln -s "$release_path" "$release_link"

sudo systemctl restart portal

echo "Staging 成果物: $DEPLOY_FIRST_ARTIFACT"
echo "リリース成果物: $release_path"
''',

  // 以下の設定は repositoryConfig.groovy から自動取得されます:
  // - gitSshCredentialsId
  // - mavenProfileChoices
  // - mavenDefaultProfile
  // - enableSonarQube
  // - sonarProjectName
  // - k8s リソース設定
)