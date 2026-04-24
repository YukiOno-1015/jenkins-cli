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
  // deployCommand 内で必要な箇所だけ個別に sudo を呼ぶため、
  // ここでは false にして deployCommand 全体は SSH 実行ユーザーとして走らせる。
  // true にすると `sudo -n bash -s` 経由で root 実行になり、
  // deployCommand 内の `$USER` が root に展開され、`chown -R $USER:$USER` が
  // 実質 no-op となって配置物が root 所有のままになる。
  deployUseSudo: false,
  deployCommand: '''
set -euo pipefail

# `DEPLOY_FIRST_ARTIFACT` は、SSH 転送後の staging 側フルパスです。
artifact_path="$DEPLOY_FIRST_ARTIFACT"
artifact_name="$(basename "$artifact_path")"
release_dir="$DEPLOY_TARGET_DIR"
release_path="$release_dir/$artifact_name"
release_link="$release_dir/portalApp.jar"
backup_suffix="$(date +%Y%m%d%H%M%S)"

echo "=== Portal デプロイ開始 ==="
echo "成果物パス: $artifact_path"
echo "成果物ファイル名: $artifact_name"
echo "リリースディレクトリ: $release_dir"
echo "リリース先ファイル: $release_path"
echo "シンボリックリンク: $release_link"

# リリースディレクトリは初回のみ root 権限で作成し、
# 以降のファイル操作（mv / install / ln）が sudo なしで完結するように
# 所有権を SSH 実行ユーザーへ移譲する。
# 過去に root 所有で配置されたファイルが残っている場合に備え、
# 配下のファイルもまとめて巻き取り直す。
sudo mkdir -p "$release_dir"
sudo chown -R "$USER":"$USER" "$release_dir"


if [ ! -f "$artifact_path" ]; then
  echo "ERROR: ステージング成果物が見つかりません: $artifact_path"
  exit 1
fi

# 既存の旧版 JAR は、今回リリースするものを除いて退避しておく。
# ディレクトリ所有権は SSH ユーザーに移譲済みのため sudo は不要。
find "$release_dir" -maxdepth 1 -type f -name 'portalApp-*.jar' ! -name "$artifact_name" -print | while read -r old_jar; do
  echo "旧版JARをバックアップ: $old_jar -> $old_jar.bak_${backup_suffix}"
  mv "$old_jar" "$old_jar.bak_${backup_suffix}"
done

# 同名ファイルが既にある場合も上書き前にバックアップする。
if [ -f "$release_path" ]; then
  echo "同名JARをバックアップ: $release_path -> $release_path.bak_${backup_suffix}"
  mv "$release_path" "$release_path.bak_${backup_suffix}"
fi

# install を sudo なしで実行することで、生成 JAR の所有者は SSH ユーザーになる。
echo "新しいJARを配置: $artifact_path -> $release_path"
install -m 0644 "$artifact_path" "$release_path"

# シンボリックリンクも sudo を外し、リンク自体の所有者を SSH ユーザーに揃える。
echo "シンボリックリンクを切り替え: $release_link -> $release_path"
rm -f "$release_link"
ln -s "$release_path" "$release_link"

echo "サービス状態確認(停止前): portal"
sudo systemctl status portal --no-pager || true

echo "サービス停止: portal"
sudo systemctl stop portal

echo "サービス状態確認(停止後): portal"
sudo systemctl status portal --no-pager || true

echo "サービス起動: portal"
sudo systemctl start portal

echo "サービス状態確認(起動後): portal"
sudo systemctl status portal --no-pager || true

echo "ステージング成果物: $DEPLOY_FIRST_ARTIFACT"
echo "リリース成果物: $release_path"
echo "=== Portal デプロイ完了 ==="
''',

  // 以下の設定は repositoryConfig.groovy から自動取得されます:
  // - gitSshCredentialsId
  // - mavenProfileChoices
  // - mavenDefaultProfile
  // - enableSonarQube
  // - sonarProjectName
  // - k8s リソース設定
)