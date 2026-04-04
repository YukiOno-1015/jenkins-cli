/*
 * Kubernetes 上の Jenkins agent で Maven / Node 系ビルドを標準化する共有パイプラインです。
 *
 * 役割:
 * - Git clone / Maven build / SonarQube / artifact archive の一連の流れを共通化する
 * - リポジトリ固有設定は `repositoryConfig()` から自動取得する
 * - 例外的な差分だけを `cfg` から上書きし、各 Jenkinsfile を薄く保つ
 */

/**
 * 標準ビルドパイプラインを生成して実行する。
 * `gitRepoUrl` は必須で、その他の値は `cfg > repositoryConfig > デフォルト値` の順で解決する。
 */
def call(Map cfg = [:]) {
    // ---- gitRepoUrlは必須 ----
    def gitRepoUrl = cfg.gitRepoUrl ?: error('gitRepoUrl is required')

    // ---- repositoryConfigから設定を取得 ----
    def repoConfig = repositoryConfig(gitRepoUrl)
    echo "Using repository configuration for: ${repoConfig.repoName}"

    // ---- 設定の優先順位: 引数 > repositoryConfig > デフォルト値 ----
    def k8sNamespace = cfg.get('namespace', 'jenkins')
    // NOTE: これは "Kubernetes Secret 名" (spec.imagePullSecrets[].name)
    def imagePullSecret = cfg.get('imagePullSecret', 'docker-hub')

    def gitBranch = cfg.get('gitBranch', 'release1.0.0')
    def configuredGitCredId = repoConfig.credentialsId?.toString()?.trim()
    def requestedGitCredId = cfg.containsKey('gitSshCredentialsId') ? cfg.get('gitSshCredentialsId') : null
    requestedGitCredId = requestedGitCredId?.toString()?.trim()
    def gitSshCredId = requestedGitCredId ?: configuredGitCredId

    if (!gitSshCredId) {
        error("Git SSH credentials ID could not be resolved for ${repoConfig.repoName}. Set repositoryConfig.credentialsId or gitSshCredentialsId.")
    }

    def gitCredSource = requestedGitCredId ? 'pipeline argument' : 'repositoryConfig'
    echo "Git SSH credentials ID: ${gitSshCredId} (source: ${gitCredSource})"

    def mavenProfileChoices = cfg.get('mavenProfileChoices', repoConfig.buildProfiles)
    def mavenDefaultProfile = cfg.get('mavenDefaultProfile', repoConfig.defaultProfile)
    def mavenCommand = cfg.get('mavenCommand', 'mvn -B clean package')

    def archivePattern = cfg.get('archivePattern', repoConfig.archivePattern)
    def skipArchive = cfg.get('skipArchive', repoConfig.skipArchive)

    // リモート配置設定。必要時だけ有効化し、通常のビルドでは何もしない。
    def enableRemoteDeploy = cfg.containsKey('enableRemoteDeploy') ? cfg.get('enableRemoteDeploy').toString().toBoolean() : false
    def deployArtifactPattern = cfg.get('deployArtifactPattern', archivePattern ?: '**/target/*.jar')?.toString()?.trim()
    def deployHost = cfg.get('deployHost', '')?.toString()?.trim()
    def deployUser = cfg.get('deployUser', '')?.toString()?.trim()
    def deployPort = (cfg.get('deployPort', 22) as Integer)
    def deployKnownHost = cfg.get('deployKnownHost', deployHost ?: '')?.toString()?.trim()
    def requestedDeployCredId = cfg.containsKey('deploySshCredentialsId') ? cfg.get('deploySshCredentialsId') : null
    requestedDeployCredId = requestedDeployCredId?.toString()?.trim()
    def deploySshCredId = requestedDeployCredId ?: gitSshCredId
    def deployTargetDir = cfg.get('deployTargetDir', '/tmp/app-deploy')?.toString()?.trim()
    def deployCommand = cfg.get('deployCommand', '')?.toString()
    def runDeployCommand = cfg.containsKey('runDeployCommand') ? cfg.get('runDeployCommand').toString().toBoolean() : false
    def deployUseSudo = cfg.containsKey('deployUseSudo') ? cfg.get('deployUseSudo').toString().toBoolean() : false

    def enableSonarQube = cfg.get('enableSonarQube', repoConfig.sonarEnabled)
    def configuredSonarCredId = repoConfig.sonarQubeCredentialsId?.toString()?.trim()
    def requestedSonarCredId = cfg.containsKey('sonarQubeCredentialsId') ? cfg.get('sonarQubeCredentialsId') : null
    requestedSonarCredId = requestedSonarCredId?.toString()?.trim()
    def sonarQubeCredId = requestedSonarCredId ?: configuredSonarCredId ?: 'sonarqube-token'
    if (!sonarQubeCredId) {
        error("SonarQube credentials ID could not be resolved for ${repoConfig.repoName}. Set repositoryConfig.sonarQubeCredentialsId or sonarQubeCredentialsId.")
    }
    def sonarCredSource = requestedSonarCredId ? 'pipeline argument' : (configuredSonarCredId ? 'repositoryConfig' : 'default')
    echo "SonarQube credentials ID: ${sonarQubeCredId} (source: ${sonarCredSource})"

    def sonarMavenPluginVersion = cfg.get('sonarMavenPluginVersion', '5.5.0.6356')
    def sonarSkipJreProvisioning = cfg.containsKey('sonarSkipJreProvisioning') ? cfg.get('sonarSkipJreProvisioning').toString().toBoolean() : true
    def sonarVerbose = cfg.containsKey('sonarVerbose') ? cfg.get('sonarVerbose').toString().toBoolean() : true
    def sonarFailFastOnPreflightError = cfg.containsKey('sonarFailFastOnPreflightError') ? cfg.get('sonarFailFastOnPreflightError').toString().toBoolean() : true
    def sonarQubeUrlRaw = cfg.get('sonarQubeUrl', 'http://sonarqube-app-sonarqube.sonarqube.svc.cluster.local:9000')
    def sonarQubeUrl = sonarQubeUrlRaw?.replaceFirst('^hhttp', 'http')
    if (sonarQubeUrl && !sonarQubeUrl.startsWith('http://') && !sonarQubeUrl.startsWith('https://')) {
        echo "⚠️  WARNING: sonarQubeUrl has invalid scheme: ${sonarQubeUrl}"
    }
    def sonarProjectName = cfg.get('sonarProjectName', repoConfig.sonarProjectName)
    def sonarBranchSuffix = sanitizeForSonarProjectKey(gitBranch)

    // K8s設定もrepositoryConfigから
    def image = cfg.get('image', repoConfig.k8s.image)
    def cpuReq = cfg.get('cpuRequest', repoConfig.k8s.cpuRequest)
    def memReq = cfg.get('memRequest', repoConfig.k8s.memRequest)
    def cpuLim = cfg.get('cpuLimit', repoConfig.k8s.cpuLimit)
    def memLim = cfg.get('memLimit', repoConfig.k8s.memLimit)

    pipeline {
        agent {
            kubernetes {
                namespace k8sNamespace
                defaultContainer 'build'
                yaml k8sPodYaml(
                    image: image,
                    imagePullSecret: imagePullSecret,
                    cpuRequest: cpuReq,
                    memRequest: memReq,
                    cpuLimit: cpuLim,
                    memLimit: memLim
                )
            }
        }

        parameters {
            string(
                name: 'gitBranch',
                defaultValue: 'release1.0.0',
                description: 'Git branch to build (default: release1.0.0)'
            )
            choice(
                name: 'mavenProfile',
                choices: mavenProfileChoices,
                description: 'Maven build profile to use'
            )
            booleanParam(
                name: 'skipTests',
                defaultValue: false,
                description: 'Skip unit tests (default: false)'
            )
            booleanParam(
                name: 'enableSonarQube',
                defaultValue: enableSonarQube,
                description: "Run SonarQube analysis (default: ${enableSonarQube})"
            )
            booleanParam(
                name: 'enableRemoteDeploy',
                defaultValue: enableRemoteDeploy,
                description: 'JAR / WAR などの成果物を SSH でリモートへ転送する'
            )
            booleanParam(
                name: 'runDeployCommand',
                defaultValue: runDeployCommand,
                description: '転送後に deployCommand を実行して配置・再起動まで行う'
            )
        }

        options {
            // Declarative の自動 Checkout SCM を無効化（Git plugin の known_hosts 問題を回避）
            skipDefaultCheckout(true)

            buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '10'))
            timeout(time: 30, unit: 'MINUTES')
            timestamps()
        }

        stages {
            stage('Clone GitHub Repo (SSH)') {
                steps {
                    container('build') {
                        script {
                            // パラメータ入力があれば、それを優先
                            def branch = params.gitBranch ?: gitBranch
                            gitCloneSsh(
                                repoUrl: gitRepoUrl,
                                branch: branch,
                                dir: 'repo',
                                sshCredentialsId: gitSshCredId,
                                knownHost: 'github.com'
                            )
                        }
                    }
                }
            }

            stage('Maven Build') {
                when {
                    expression { enableSonarQube == false }
                }
                steps {
                    container('build') {
                        dir('repo') {
                            sh """#!/bin/bash
                              set -euo pipefail

                              echo "=== Preflight: repo/.git and open handles ==="
                              ls -la .git || true
                              lsof | grep repo || true

                              echo "=== Maven Version ==="
                              mvn -v

                              echo "=== Building with profile: ${mavenDefaultProfile} ==="

                              ${mavenCommand} -P "${mavenDefaultProfile}" -DskipTests=false 2>&1 | grep -v "The requested profile" || true
                            """
                        }
                    }
                }
            }

            stage('Maven Build(SonarQube Analysis)') {
                when {
                    expression { enableSonarQube == true }
                }
                steps {
                    container('build') {
                        dir('repo') {
                            // catchError: SonarQube失敗はビルド全体をFAILUREにしない（UNSTABLEに留める）
                            catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
                                withCredentials([string(credentialsId: sonarQubeCredId, variable: 'SONAR_TOKEN')]) {
                                    sh """#!/bin/bash
                                      set -eo pipefail

                                      echo "=== Maven Version ==="
                                      mvn -v

                                      echo "=== Running SonarQube Analysis Building with profile: ${mavenDefaultProfile} ==="
                                      echo "SonarQube URL: ${sonarQubeUrl}"
                                                                            echo "SonarQube credentials ID: ${sonarQubeCredId}"
                                                                            echo "Sonar Maven plugin version: ${sonarMavenPluginVersion}"
                                                                            echo "Sonar skip JRE provisioning: ${sonarSkipJreProvisioning}"
                                                                            echo "Sonar verbose: ${sonarVerbose}"
                                                                            echo "Sonar fail-fast on preflight error: ${sonarFailFastOnPreflightError}"

                                      # SONAR_TOKEN の事前検証（-u なしでも明示チェック）
                                      if [ -z "\${SONAR_TOKEN:-}" ]; then
                                        echo "ERROR: SONAR_TOKEN is empty or not set."
                                        echo "  Verify that the Jenkins credential '${sonarQubeCredId}' exists and has a valid Secret Text value."
                                        exit 1
                                      fi
                                                                                                                                                        SONAR_TOKEN_CLEAN="\$(printf '%s' "\${SONAR_TOKEN}" | tr -d '\r\n')"
                                                                                                                                                        if [ -z "\${SONAR_TOKEN_CLEAN}" ]; then
                                                                                                                                                                echo "ERROR: SONAR_TOKEN becomes empty after trimming CR/LF."
                                                                                                                                                                echo "  Verify Jenkins credential '${sonarQubeCredId}' does not contain only whitespace/newlines."
                                                                                                                                                                exit 1
                                                                                                                                                        fi
                                                                                                                                                        if [ "\${SONAR_TOKEN_CLEAN}" != "\${SONAR_TOKEN}" ]; then
                                                                                                                                                                echo "WARNING: SONAR_TOKEN contained CR/LF and was normalized before use."
                                                                                                                                                        fi

                                                                            echo "=== SonarQube Connectivity Preflight ==="
                                                                            SONAR_HTTP_CODE="\$(curl -sS -o /tmp/sonar_server_version.txt -w "%{http_code}" "${sonarQubeUrl}/api/server/version" || true)"
                                                                            SONAR_SERVER_VERSION="\$(cat /tmp/sonar_server_version.txt 2>/dev/null || true)"
                                                                            echo "SonarQube /api/server/version HTTP status: \${SONAR_HTTP_CODE}"
                                                                            if [ -n "\${SONAR_SERVER_VERSION}" ]; then
                                                                                echo "SonarQube server version: \${SONAR_SERVER_VERSION}"
                                                                            fi
                                                                            if [ "\${SONAR_HTTP_CODE}" = "000" ]; then
                                                                                echo "WARNING: SonarQube endpoint is unreachable from this pod. Check DNS/network/service URL."
                                                                            fi

                                                                            echo "=== SonarQube Token Preflight ==="
                                                                            SONAR_AUTH_HTTP_CODE="\$(curl -sS -o /tmp/sonar_auth_validate.json -w "%{http_code}" -H "Authorization: Bearer \${SONAR_TOKEN_CLEAN}" "${sonarQubeUrl}/api/authentication/validate" || true)"
                                                                            SONAR_AUTH_VALID="false"
                                                                            if [ "\${SONAR_AUTH_HTTP_CODE}" = "200" ] && grep -q '"valid"[[:space:]]*:[[:space:]]*true' /tmp/sonar_auth_validate.json 2>/dev/null; then
                                                                                SONAR_AUTH_VALID="true"
                                                                            fi

                                                                            if [ "\${SONAR_AUTH_VALID}" != "true" ]; then
                                                                                echo "Bearer validation did not confirm a valid token (HTTP \${SONAR_AUTH_HTTP_CODE}); retrying with basic token auth."
                                                                                SONAR_AUTH_HTTP_CODE="\$(curl -sS -o /tmp/sonar_auth_validate.json -w "%{http_code}" -u "\${SONAR_TOKEN_CLEAN}:" "${sonarQubeUrl}/api/authentication/validate" || true)"
                                                                                if [ "\${SONAR_AUTH_HTTP_CODE}" = "200" ] && grep -q '"valid"[[:space:]]*:[[:space:]]*true' /tmp/sonar_auth_validate.json 2>/dev/null; then
                                                                                    SONAR_AUTH_VALID="true"
                                                                                else
                                                                                    SONAR_AUTH_VALID="false"
                                                                                fi
                                                                            fi
                                                                            echo "SonarQube /api/authentication/validate HTTP status: \${SONAR_AUTH_HTTP_CODE}"
                                                                            echo "SonarQube token validate result: \${SONAR_AUTH_VALID}"

                                                                            if [ "\${SONAR_AUTH_HTTP_CODE}" != "200" ] || [ "\${SONAR_AUTH_VALID}" != "true" ]; then
                                                                                echo "ERROR: SonarQube token preflight failed (HTTP \${SONAR_AUTH_HTTP_CODE})."
                                                                                echo "  Verify Jenkins credential '${sonarQubeCredId}' has a valid token with Execute Analysis permission."
                                                                                if [ "${sonarFailFastOnPreflightError}" = "true" ]; then
                                                                                    exit 1
                                                                                fi
                                                                            fi

                                      echo "=== Building with profile: ${mavenDefaultProfile} ==="

                                      PROJECT_NAME="${sonarProjectName}"
                                                                            mvn -e clean verify -P "${mavenDefaultProfile}" -DskipTests=false \
                                      org.sonarsource.scanner.maven:sonar-maven-plugin:${sonarMavenPluginVersion}:sonar \
                                                                                -Dsonar.projectKey=\${PROJECT_NAME}_${sonarBranchSuffix} \
                                                                                -Dsonar.projectName=\${PROJECT_NAME}_${sonarBranchSuffix} \
                                        -Dsonar.host.url=${sonarQubeUrl} \
                                                                                -Dsonar.token=\${SONAR_TOKEN_CLEAN} \
                                                                                -Dsonar.verbose=${sonarVerbose} \
                                                                                -Dsonar.scanner.skipJreProvisioning=${sonarSkipJreProvisioning}
                                    """
                                }
                            }
                        }
                    }
                }
                post {
                    success {
                        echo "✅ SonarQube analysis completed - check results at ${sonarQubeUrl}"
                    }
                    unstable {
                        echo "⚠️ SonarQube analysis failed - build marked UNSTABLE. Check credential '${sonarQubeCredId}', token preflight logs, and scanner bootstrap logs."
                    }
                    failure {
                        echo "⚠️ SonarQube analysis failed - check console logs for details"
                    }
                }
            }

            stage('Archive Artifacts') {
                when {
                    expression { skipArchive == false && archivePattern }
                }
                steps {
                    archiveArtifacts(
                        artifacts: archivePattern,
                        fingerprint: true,
                        allowEmptyArchive: true
                    )
                }
            }

            stage('Remote Deploy') {
                when {
                    expression { params.enableRemoteDeploy }
                }
                steps {
                    container('build') {
                        script {
                            if (!deployHost) {
                                error('deployHost is required when enableRemoteDeploy=true')
                            }
                            if (!deployUser) {
                                error('deployUser is required when enableRemoteDeploy=true')
                            }
                            if (!deploySshCredId) {
                                error('deploySshCredentialsId could not be resolved for remote deploy')
                            }
                            if (!deployArtifactPattern) {
                                error('deployArtifactPattern is required when enableRemoteDeploy=true')
                            }

                            def matchedArtifacts = findFiles(glob: deployArtifactPattern)
                                .findAll { !it.directory }
                                .collect { it.path }
                                .sort()

                            if (!matchedArtifacts) {
                                error("No deployment artifacts matched pattern: ${deployArtifactPattern}")
                            }

                            def remoteArtifactPaths = matchedArtifacts.collect { path ->
                                "${deployTargetDir}/${path.tokenize('/').last()}"
                            }

                            echo "Remote deploy target: ${deployUser}@${deployHost}:${deployTargetDir}"
                            echo "Deployment artifacts: ${matchedArtifacts.join(', ')}"

                            // 配置先は scp で書き込める staging ディレクトリを想定する。
                            // 本番ディレクトリへの反映やサービス再起動は deployCommand 側で行う。
                            remoteSsh(
                                host: deployHost,
                                user: deployUser,
                                sshCredentialsId: deploySshCredId,
                                port: deployPort,
                                knownHost: deployKnownHost,
                                strictHostKeyChecking: true,
                                command: "mkdir -p ${shellQuote(deployTargetDir)}"
                            )

                            sshagent(credentials: [deploySshCredId]) {
                                sh """#!/bin/bash
                                  set -euo pipefail

                                  command -v scp >/dev/null 2>&1 || {
                                    echo 'scp command not found in build container'
                                    exit 1
                                  }

                                  mkdir -p ~/.ssh
                                  chmod 700 ~/.ssh
                                  touch ~/.ssh/known_hosts
                                  chmod 600 ~/.ssh/known_hosts

                                  ssh-keyscan -p ${deployPort} -t rsa,ecdsa,ed25519 -H ${shellQuote(deployKnownHost)} >> ~/.ssh/known_hosts 2>/dev/null || true
                                  sort -u ~/.ssh/known_hosts -o ~/.ssh/known_hosts || true

                                  SCP_OPTIONS=(
                                    -o BatchMode=yes
                                    -o ConnectTimeout=10
                                    -o StrictHostKeyChecking=yes
                                    -o UserKnownHostsFile=\"$HOME/.ssh/known_hosts\"
                                    -P ${deployPort}
                                  )

                                  for artifact in ${matchedArtifacts.collect { shellQuote(it) }.join(' ')}; do
                                    echo "Uploading ${artifact} -> ${deployUser}@${deployHost}:${deployTargetDir}/"
                                    scp "\${SCP_OPTIONS[@]}" "$artifact" ${shellQuote("${deployUser}@${deployHost}:${deployTargetDir}/")}
                                  done
                                """
                            }

                            if (deployCommand?.trim() && params.runDeployCommand) {
                                def remoteDeployCommand = """
export DEPLOY_TARGET_DIR=${shellQuote(deployTargetDir)}
export DEPLOY_ARTIFACT_PATHS=${shellQuote(remoteArtifactPaths.join('\n'))}
export DEPLOY_FIRST_ARTIFACT=${shellQuote(remoteArtifactPaths.first())}

${deployCommand}
""".trim()

                                remoteSsh(
                                    host: deployHost,
                                    user: deployUser,
                                    sshCredentialsId: deploySshCredId,
                                    port: deployPort,
                                    knownHost: deployKnownHost,
                                    useSudo: deployUseSudo,
                                    strictHostKeyChecking: true,
                                    command: remoteDeployCommand
                                )
                            } else if (deployCommand?.trim()) {
                                echo 'runDeployCommand=false のため、成果物転送のみ実行しました。'
                            } else {
                                echo 'deployCommand is empty. Artifact upload only completed.'
                            }
                        }
                    }
                }
            }
        }

        post {
            success {
                echo "✅ Build SUCCESS (profile: ${mavenDefaultProfile})"
            }
            unstable {
                echo "⚠️ Build UNSTABLE (profile: ${mavenDefaultProfile}) - check SonarQube result and credentials"
            }
            failure {
                echo "❌ Build FAILED - check console logs for details"
            }
            cleanup {
                script {
                    // Pod 起動失敗/Checkout前失敗などで agent(workspace) が無い場合はスキップ
                    if (!env.NODE_NAME || !env.WORKSPACE) {
                        echo "🧹 Skip cleanup: agent/workspace not available (NODE_NAME=${env.NODE_NAME}, WORKSPACE=${env.WORKSPACE})"
                        return
                    }

                    container('build') {
                        // soften permissions/ownership so workspace cleanup can proceed
                        sh '''#!/bin/bash
                          set -euo pipefail
                          chown -R "$(id -u)":"$(id -g)" . || true
                          chmod -R u+rwX . || true
                        '''
                        try {
                            deleteDir()
                        } catch (err) {
                            echo "deleteDir() failed (${err}); fallback to rm -rf"
                            sh '''#!/bin/bash
                              set -euo pipefail
                              rm -rf -- ./* ./.??* || true
                            '''
                        }
                    }
                }
            }
        }
    }
}

/**
 * シェル引数として安全に埋め込めるよう、値を単一引用符でエスケープする。
 */
private String shellQuote(String value) {
    return "'${(value ?: '').replace("'", "'\"'\"'")}'"
}

/**
 * SonarQube の projectKey として利用できるよう、ブランチ名を安全な文字列へ正規化する。
 */
private String sanitizeForSonarProjectKey(String value) {
    def sanitized = (value ?: 'main').replaceAll('[^A-Za-z0-9_.:-]', '_')
    return sanitized ?: 'main'
}