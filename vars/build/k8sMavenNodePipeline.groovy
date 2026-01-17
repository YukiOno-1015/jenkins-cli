def call(Map cfg = [:]) {
    // ---- gitRepoUrlは必須 ----
    def gitRepoUrl = cfg.gitRepoUrl ?: error('gitRepoUrl is required')
    
    // ---- repositoryConfigから設定を取得 ----
    def repoConfig = repositoryConfig(gitRepoUrl)
    echo "Using repository configuration for: ${repoConfig.repoName}"
    
    // ---- 設定の優先順位: 引数 > repositoryConfig > デフォルト値 ----
    def namespace = cfg.get('namespace', 'jenkins')
    def imagePullSecret = cfg.get('imagePullSecret', 'dockerhub-jenkins-agent')

    def gitBranch = cfg.get('gitBranch', 'main')
    def gitSshCredId = cfg.get('gitSshCredentialsId', repoConfig.credentialsId)

    def mavenProfileChoices = cfg.get('mavenProfileChoices', repoConfig.buildProfiles)
    def mavenDefaultProfile = cfg.get('mavenDefaultProfile', repoConfig.defaultProfile)
    def mavenCommand = cfg.get('mavenCommand', 'mvn -B clean package')

    def archivePattern = cfg.get('archivePattern', repoConfig.archivePattern)
    def skipArchive = cfg.get('skipArchive', repoConfig.skipArchive)

    def enableSonarQube = cfg.get('enableSonarQube', repoConfig.sonarEnabled)
    def sonarQubeCredId = cfg.get('sonarQubeCredentialsId', 'sonarQubeCredId')
    def sonarQubeUrl = cfg.get('sonarQubeUrl', 'https://sonar-svc.sk4869.info')
    def sonarProjectName = cfg.get('sonarProjectName', repoConfig.sonarProjectName)

    // K8s設定もrepositoryConfigから
    def image = cfg.get('image', repoConfig.k8s.image)
    def cpuReq = cfg.get('cpuRequest', repoConfig.k8s.cpuRequest)
    def memReq = cfg.get('memRequest', repoConfig.k8s.memRequest)
    def cpuLim = cfg.get('cpuLimit', repoConfig.k8s.cpuLimit)
    def memLim = cfg.get('memLimit', repoConfig.k8s.memLimit)

    pipeline {
        agent {
            kubernetes {
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
                name: 'GIT_REPO_URL',
                defaultValue: gitRepoUrl,
                description: 'GitHub SSH repo URL'
            )
            string(
                name: 'GIT_BRANCH',
                defaultValue: gitBranch,
                description: 'Git branch to checkout'
            )
            credentials(
                name: 'GIT_SSH_CREDENTIALS_ID',
                defaultValue: gitSshCredId,
                description: 'SSH credentials ID for GitHub',
                required: true
            )
            choice(
                name: 'MAVEN_PROFILE',
                choices: mavenProfileChoices,
                description: 'Maven profile to use for build'
            )
            booleanParam(
                name: 'ENABLE_SONARQUBE',
                defaultValue: enableSonarQube,
                description: 'Run SonarQube analysis'
            )
        }

        options {
            buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '10'))
            timeout(time: 30, unit: 'MINUTES')
            timestamps()
        }

        stages {
            stage('Clone GitHub Repo (SSH)') {
                steps {
                    container('build') {
                        script {
                            gitCloneSsh(
                                repoUrl: params.GIT_REPO_URL,
                                branch: params.GIT_BRANCH,
                                dir: 'repo',
                                sshCredentialsId: gitSshCredId,
                                knownHost: 'github.com'
                            )
                        }
                    }
                }
            }

            stage('Maven Build') {
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
                              
                              echo "=== Building with profile: \${MAVEN_PROFILE} ==="
                              ${mavenCommand} -P "\${MAVEN_PROFILE}" -DskipTests=false 2>&1 | grep -v "The requested profile" || true
                            """
                        }
                    }
                }
            }

            stage('SonarQube Analysis') {
                when {
                    expression { params.ENABLE_SONARQUBE == true }
                }
                steps {
                    container('build') {
                        dir('repo') {
                            withCredentials([string(credentialsId: sonarQubeCredId, variable: 'SONAR_TOKEN')]) {
                                sh """#!/bin/bash
                                  set -euo pipefail
                                  
                                  echo "=== Running SonarQube Analysis ==="
                                  echo "SonarQube URL: ${sonarQubeUrl}"
                                  
                                  PROJECT_NAME="${sonarProjectName}"
                                  mvn clean verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
                                    -Dsonar.projectKey=\${PROJECT_NAME} \
                                    -Dsonar.projectName=\${PROJECT_NAME} \
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
                echo "✅ Build SUCCESS (profile: \${params.MAVEN_PROFILE})"
            }
            failure {
                echo "❌ Build FAILED - check console logs for details"
            }
            cleanup {
                container('build') {
                    // soften permissions/ownership so workspace cleanup can proceed
                    sh '''#!/bin/bash
                      set -euo pipefail
                      chown -R "$(id -u)":"$(id -g)" . || true
                      chmod -R u+rwX . || true
                    '''
                    script {
                        try {
                            deleteDir()
                        } catch (err) {
                            echo "deleteDir() failed (${err}); fallback to rm -rf"
                            sh """#!/bin/bash
                              set -euo pipefail
                              rm -rf -- ./* ./.??* || true
                            """
                        }
                    }
                }
            }
        }
    }
}