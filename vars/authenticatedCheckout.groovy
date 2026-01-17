// Authenticated Git Checkout Helper
// リポジトリに応じた認証情報を自動選択してGit操作を実行

def call(Map args = [:]) {
  // 必須パラメータのバリデーション
  if (!args.repoUrl && !env.GIT_URL) {
    error('repoUrl is required or GIT_URL must be set')
  }
  
  def repoUrl = args.repoUrl ?: env.GIT_URL
  def branch = args.get('branch', env.GIT_BRANCH ?: 'main')
  def targetDir = args.get('dir', '.')
  
  // リポジトリ設定を一括取得（認証情報を含む）
  def config = repositoryConfig(repoUrl)
  def repoName = config.repoName
  def credentialsId = config.credentialsId
  
  echo "========================================="
  echo "Authenticated Git Checkout"
  echo "========================================="
  echo "Repository: ${repoName}"
  echo "URL: ${repoUrl}"
  echo "Branch: ${branch}"
  echo "Credentials: ${credentialsId}"
  echo "Target Directory: ${targetDir}"
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
      echo "Current commit details:"
      git log -1 --pretty=format:"Commit: %H%nAuthor: %an <%ae>%nDate: %ad%nMessage: %s" --date=format:"%Y-%m-%d %H:%M:%S"
      echo ""
      echo ""
      echo "Changed files:"
      git diff-tree --no-commit-id --name-status -r HEAD || echo "No changes detected"
    '''
  }
  
  return [
    repoName: repoName,
    credentialsId: credentialsId,
    branch: branch,
    config: config  // 完全な設定も返す
  ]
}

// シンプルな認証情報取得のみの関数
def getCredentials(String repoUrl = null) {
  def url = repoUrl ?: env.GIT_URL
  if (!url) {
    error('repoUrl is required or GIT_URL must be set')
  }
  
  // リポジトリ設定を取得
  def config = repositoryConfig(url)
  
  return [
    repoName: config.repoName,
    credentialsId: config.credentialsId,
    config: config  // 完全な設定も返す
  ]
}
