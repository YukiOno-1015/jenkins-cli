/*
 * リポジトリ設定に応じて認証情報を自動解決し、checkout を実行するヘルパーです。
 *
 * 主な役割:
 * - `repositoryConfig()` から対象リポジトリの設定を取得する
 * - Jenkins 環境変数へ `REPO_NAME` / `GIT_CREDENTIALS_ID` を保存する
 * - `checkout scm` または `gitCloneSsh()` のどちらかで実際の取得処理を行う
 */

/**
 * 認証付き checkout を実行するエントリポイント。
 *
 * 想定引数:
 * - `repoUrl`: 対象リポジトリ URL（未指定時は `env.GIT_URL` を利用）
 * - `branch`: checkout 対象ブランチ
 * - `dir`: 配置先ディレクトリ
 * - `useScm`: true の場合は `checkout scm` を優先
 */
def call(Map args = [:]) {
  // 必須パラメータのバリデーション
  if (!args.repoUrl && !env.GIT_URL) {
    error('repoUrl または GIT_URL の設定が必要です。')
  }
  
  def repoUrl = args.repoUrl ?: env.GIT_URL
  def branch = args.get('branch', env.GIT_BRANCH ?: 'main')
  def targetDir = args.get('dir', '.')
  
  // リポジトリ設定を一括取得（認証情報を含む）
  def config = repositoryConfig(repoUrl)
  def repoName = config.repoName
  def credentialsId = config.credentialsId
  
  echo "========================================="
  echo "認証付き Git チェックアウト"
  echo "========================================="
  echo "リポジトリ: ${repoName}"
  echo "URL: ${repoUrl}"
  echo "ブランチ: ${branch}"
  echo "認証情報: ${credentialsId}"
  echo "配置先ディレクトリ: ${targetDir}"
  echo "========================================="
  
  // 認証情報を環境変数に保存（他のステージで参照可能）
  env.GIT_CREDENTIALS_ID = credentialsId
  env.REPO_NAME = repoName
  
  // checkout scmを使う場合（Multibranch Pipeline推奨）
  if (args.get('useScm', true)) {
    checkout scm
  } else {
    // 明示的にgitCloneSshを使う場合
    gitCloneSsh(
      repoUrl: repoUrl,
      branch: branch,
      sshCredentialsId: credentialsId,
      dir: targetDir
    )
  }
  
  // コミット情報の表示
  dir(targetDir) {
    sh '''
      echo "最新コミット詳細:"
      git log -1 --pretty=format:"Commit: %H%nAuthor: %an <%ae>%nDate: %ad%nMessage: %s" --date=format:"%Y-%m-%d %H:%M:%S"
      echo ""
      echo ""
      echo "変更ファイル一覧:"
      git diff-tree --no-commit-id --name-status -r HEAD || echo "変更ファイルなし"
    '''
  }
  
  return [
    repoName: repoName,
    credentialsId: credentialsId,
    branch: branch,
    config: config  // 完全な設定も返す
  ]
}

/**
 * checkout は行わず、対象リポジトリに必要な認証情報だけを取得する補助関数。
 * 他の Shared Library から資格情報解決だけを再利用したい場合に使う。
 */
def getCredentials(String repoUrl = null) {
  def url = repoUrl ?: env.GIT_URL
  if (!url) {
    error('repoUrl または GIT_URL の設定が必要です。')
  }
  
  // リポジトリ設定を取得
  def config = repositoryConfig(url)
  
  return [
    repoName: config.repoName,
    credentialsId: config.credentialsId,
    config: config  // 完全な設定も返す
  ]
}
