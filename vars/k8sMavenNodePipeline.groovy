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
    def gitSshCredId = cfg.get('gitSshCredentialsId', repoConfig.credentialsId)

    def mavenProfileChoices = cfg.get('mavenProfileChoices', repoConfig.buildProfiles)
    def mavenDefaultProfile = cfg.get('mavenDefaultProfile', repoConfig.defaultProfile)
    def mavenCommand = cfg.get('mavenCommand', 'mvn -B clean package')

    def archivePattern = cfg.get('archivePattern', repoConfig.archivePattern)
    def skipArchive = cfg.get('skipArchive', repoConfig.skipArchive)

    def enableSonarQube = cfg.get('enableSonarQube', repoConfig.sonarEnabled)
    def sonarQubeCredId = cfg.get('sonarQubeCredentialsId', 'sonarqube-token')
    def sonarQubeUrlRaw = cfg.get('sonarQubeUrl', 'http://sonarqube-sonarqube.sonarqube.svc.cluster.local:9000')
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
                            withCredentials([string(credentialsId: sonarQubeCredId, variable: 'SONAR_TOKEN')]) {
                                sh """#!/bin/bash
                                  set -euo pipefail
                                  echo "=== Maven Version ==="
                                  mvn -v

                                  echo "=== Running SonarQube Analysis Building with profile: ${mavenDefaultProfile} ==="
                                  echo "SonarQube URL: ${sonarQubeUrl}"

                                  echo "=== Building with profile: ${mavenDefaultProfile} ==="

                                  PROJECT_NAME="${sonarProjectName}"
                                  mvn clean verify -P "${mavenDefaultProfile}" -DskipTests=false \
                                  org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
                                                                        -Dsonar.projectKey=\${PROJECT_NAME}_${sonarBranchSuffix} \
                                                                        -Dsonar.projectName=\${PROJECT_NAME}_${sonarBranchSuffix} \
                                    -Dsonar.host.url=${sonarQubeUrl} \
                                    -Dsonar.token=\${SONAR_TOKEN} || true
                                """
                            }
                        }
                    }
                }
                post {
                    success {
                        echo "✅ SonarQube analysis completed - check results at ${sonarQubeUrl}"
                    }
                    failure {
                        echo "⚠️ SonarQube analysis failed - will continue build"
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
        }

        post {
            success {
                echo "✅ Build SUCCESS (profile: ${mavenDefaultProfile})"
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

private String sanitizeForSonarProjectKey(String value) {
    def sanitized = (value ?: 'main').replaceAll('[^A-Za-z0-9_.:-]', '_')
    return sanitized ?: 'main'
}