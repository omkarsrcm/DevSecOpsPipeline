def call(body) {
    def opsUtils = new com.omvexis.devops.OpsUtils()
    def map = [:]
    // Load the YAML content from the file
    def podConfig = libraryResource 'files/jenkins-k8s-files/jenkins-k8s-agent-files/default-agents-pod.yaml'
    echo 'Default K8s Template'
/* This Pipeline is built for omvexis_Application. */
    pipeline {
        agent {
            kubernetes {
                // Use the pod configuration loaded from YAML file
                yaml podConfig
                customWorkspace "/home/jenkins/agent/${JOB_NAME}/${Cluster_Type}/"
                retries 5
            }
        }
        environment {
            Azure_URL = 'dev.azure.com/omkarkulkarni440128/omvexis/_git'
            Git_Repo_Name = "central"
            Conf_Filename = 'template.conf'
            CumulusConfigID = 'dc2fb579-39ae-4fb9-9eda-484da909c3a1'
            MainConfigFileName = "omvexis_config.yaml" // This name is name of main app config file in cumulus-config
            SnykApi = credentials('snyk_api_token')
            }
        parameters {
            choice(choices: ['commercial', 'federal'], description: 'Please select Federal for ironbank images,otherwise leave it at the default option', name: 'Docker_Build_Type')
            string(defaultValue: '', description: 'Please provide the branch_name for cloning the repo.', name: 'BranchName', trim: false)
            booleanParam(name: 'Deployment', defaultValue: false, description: 'Please select if you want to deploy the docker image.')
            string defaultValue: '', description: 'Please Provide Scripts Name in Lower-Case only you want to run,Multi script name can be passed eg (migrate.py,bootstrap.py),Otherwise leave it blank ', name: 'Script_Name', trim: true
            choice(choices: ['hfnlife-ui', 'hfnlife_products','hfnlife_orders','hfnlife_user'], description: 'Please select the service to be built', name: 'Repo_Name')
            choice(choices: ['hfnlife_dev', 'hfnlife_prd'], description: 'Please select the cluster', name: 'Cluster_Type')
        }

        stages {
            stage('Trigger Validation') {
                steps {
                    script {
                        wrap([$class: 'BuildUser']) {
                            env.Userid = "\"$env.BUILD_USER_ID\"".toLowerCase()
                            echo "${Userid} triggered the pipeline."
                        }
                    }
                }
            }
            stage('Initialize Pipeline Config') {
                parallel{
                    stage('Registry Config'){
                       steps {
                            script {
                                currentBuild.displayName = "${params.Cluster_Type}-${params.Repo_Name}-${BUILD_NUMBER}"
                                currentBuild.description = "Branch_Name: ${params.BranchName}\n" + "Deployment_Selected: ${Deployment}\n"
                                def filename = "cluster.json"
                                def clusterjsoncontent = libraryResource("files/${filename}")
                                writeFile(file: "${filename}", text: clusterjsoncontent)
                                def cluster_environment_variables = readJSON file: "${filename}"
                                if (params.Cluster_Type ==~ /^prod.*/){
                                    echo "##### Use Prod_Registry #####"
                                    env.RegistryUrl = cluster_environment_variables["cluster"]["PrdRegistry"]
                                    env.Registry_Cred = cluster_environment_variables["cluster"]["PrdRegistryCredId"]
                                    env.Default_RegUrl = cluster_environment_variables["cluster"]["defaultRegUrl"]
                                } else {
                                    echo "##### Use Non_Prod_Registry #####"
                                    env.RegistryUrl = cluster_environment_variables["cluster"]["PrdRegistry"]
                                    env.Registry_Cred = cluster_environment_variables["cluster"]["nonPrdRegistryCredId"]
                                    env.Default_RegUrl = cluster_environment_variables["cluster"]["defaultRegUrl"]
                                }
                                env.TimeStamp = "1.0.0-${sh(script: 'date +\\%Y\\%m\\%d', returnStdout: true).trim()}"
                                
                            }   
                        }
                    }

                    stage('ImageType'){
                       steps {
                            script {
                                    if (params.Docker_Build_Type == 'federal') {
                                        env.dockerfile = "Dockerfile.ironbank"
                                    } else {
                                        env.dockerfile = "Dockerfile"
                                    }
                            }   
                        }
                    }
                    stage("Security: Auth Snyk Account"){
                        steps{
                            script{
                                    echo "##### Authenticating Snyk Account #####"
                                    sh "snyk auth ${SnykApi}"
                            }
                        }
                    }
                }                
            }
            stage('Clone and Checkout Repository') {
                steps {
                    script {
                        dir("${env.WORKSPACE}/${params.Repo_Name}") {
                            withCredentials([usernamePassword(credentialsId: 'jenkins_ops_lib', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                                echo "###### The BranchName has been set to ${BranchName} ######"
                                sh """
                                    git clone https://$USERNAME:$PASSWORD@${Azure_URL}/${params.Repo_Name} --depth=1 --branch ${params.BranchName} .
                                    ls -l
                                """
                                env.CommitId = sh(script: "echo `git log --format=%H -n 1`", returnStdout: true).trim()
                            }
                        }
                    }
                }
            }
            stage("Static Analysis"){
                parallel{
                    stage("Unit Tests"){
                        steps{
                            script{
                                dir("${env.WORKSPACE}/${params.Repo_Name}"){
                                    env.Env_Dir_UT="venv1"
                                    echo "##### Create a python ve #####"
                                    sh "python3.9 -m venv ${Env_Dir_UT}"
                                    echo "##### Install temporary pip package #####"
                                    sh '''
                                        . ${Env_Dir_UT}/bin/activate
                                        pip3.9 install -r requirements_unit_test.txt
                                    '''
                                    echo "##### Running unit_test #####"

                                    env.UnitTestResultStatus = sh(script: '''
                                        . ${Env_Dir_UT}/bin/activate
                                        pip3.9 list
                                        pytest test.py
                                    ''', returnStatus: true)

                                    echo "UnitTestResultStatus: ${env.UnitTestResultStatus}"
                                    if(env.UnitTestResultStatus != "0"){
                                        error("Unit test failed, Please check !!!")
                                    }else {
                                        echo "##### Unit Test Passed #####"
                                    }
                                }
                            }
                        }

                    }
                    stage("Security:SCA"){
                        steps{
                            script{
                                    dir("${env.WORKSPACE}/${params.Repo_Name}"){
                                        echo "#### Snyk open source vulnerabilities initialized #####"

                                        env.Env_Dir_SCA="venv2"
                                        sh "python3.9 -m venv ${Env_Dir_SCA}"
                                        
                                        echo"Snyk bulding complete dependency tree via pip install"
                                        sh '''
                                            . ${Env_Dir_UT}/bin/activate
                                            pip3.9 install -r requirements.txt
                                        '''
                                        env.SnykExitCode = sh(script: '''
                                                                . ${Env_Dir_UT}/bin/activate
                                                                snyk test --command=python3.9 --json-file-output=results-opensource.json
                                                            ''', returnStatus: true)

                                        echo "Exit Code: ${SnykExitCode}"

                                        echo "##### Archive open source vulnerabilities report"
                                        sh "snyk-to-html -i results-opensource.json -o snyk-sca-report.html"
                                        archiveArtifacts artifacts: 'snyk-sca-report.html', fingerprint: true

                                        if(env.SnykExitCode != "0"){
                                            error("##### Open source vulnerabilities found,Please fix !!! #####")
                                        }else {
                                            echo "##### No vulnerabilities #####"
                                        }
                                    }
                            }
                        }

                    }
                    stage("Security:SAST"){
                        steps{
                            script{
                                dir("${env.WORKSPACE}/${params.Repo_Name}"){
                                     echo "#### Snyk code vulnerabilities initialized #####"
                                     env.SnykCodeTestExitCode = sh(script: "snyk code test  --json-file-output=sast_code_results.json", returnStatus: true)
                                     if (env.SnykCodeTestExitCode != "0"){
                                            echo "##### Archive vulnerabilities report #####"
                                            sh "snyk-to-html -i sast_code_results.json -o snyk-sast-report.html"
                                            archiveArtifacts artifacts: 'snyk-sast-report.html', fingerprint: true
                                            error("##### security vulnerabilities found in code,Please fix !!! #####")
                                     }else {
                                            echo "##### No security vulnerabilities #####"
                                     }
                                }
                            }
                        }
                    }

                }
            }
            stage("Template Config: Verification & Encoding") {
                steps {
                    script {
                        dir("${env.WORKSPACE}/${Repo_Name}") {
                            if (fileExists('template.conf')) {
                                echo 'template.conf is present'
                                env.Encoded_Conf = opsUtils.base64_encode("${env.Conf_Filename}")
                            } else {
                                currentBuild.result = 'ABORTED'
                                error('Template.conf not present in this branch, please check!!!')
                            }
                        }
                    }
                }
            }
            stage("Initialize Build Image") {
                parallel{
                   stage("Container Registry Login"){
                        steps {
                            script {
                                    opsUtils.dockerLogin("${Default_RegUrl}","${Registry_Cred}")
                            }
                        }
                    }
                   stage("Build Image"){
                        steps {
                            script {
                                    dir("${env.WORKSPACE}/${env.Repo_Name}") {
                                         sh "rm -rf ${Env_Dir_UT} ${Env_Dir_SCA}"
                                         def labels = ['cs_gitcommit': env.CommitId, 'cs_template': Encoded_Conf]
                                         opsUtils.dockerbuild("${RegistryUrl}","${Registry_Cred}","${Docker_Build_Type}","${dockerfile}", "${Repo_Name}", "${TimeStamp}", labels, "${Cluster_Type}")
                                }
                                echo "Image build and successfully pushed to the docker registry"
                            }
                        }
                    }
                }
            }
            stage("Image Analysis") {
                parallel{
                   stage("Security: Image scan for vulnerabilities"){
                        steps {
                            script {
                                echo "##### Image scan initialized #####"
                                env.ImageTestExitCode = sh(script: "trivy image --exit-code=1 omkarsrcm/${params.Repo_Name}_${Docker_Build_Type}_${Cluster_Type}:${TimeStamp}", returnStatus: true)
                                if (env.ImageTestExitCode !="0"){
                                    error("##### Image scan failed,Please check pipeline logs to view  vulnerabilities !!!!")
                                }else {
                                            echo "##### Image scan completed,No vulnerabilities #####"
                                }
                            }
                        }
                   }  
                }
            }

            stage("Docker image deployer") {
                when {
                    environment name: 'Deployment', value: 'true'
                }
                steps {
                    script {
                        echo "Deploying docker image on ${Cluster_Type} server"
                        build job: 'omvexis_service_deployer', 
                        parameters: [string(name: 'Docker_Build_Type', value: "${Docker_Build_Type}"), 
                        string(name: 'Tag_No', value: "${TimeStamp}"), 
                        string(name: 'Cluster_Type', value: "${Cluster_Type}"), 
                        string(name: 'Script_Name', value: "${Script_Name}"), 
                        string(name: 'Service_Name', value: "${Repo_Name}")]
                        echo "Deployment has been completed"
                    }
                }
            }
        }
        post {
            failure {
                office365ConnectorSend webhookUrl: opsUtils.sendBuilderNotificationURL(env.Cluster_Type),
                        message: "Service Builder has failed for Service: ${Repo_Name} and Cluster: ${Cluster_Type}",
                        status: 'Failure'
            }
        }
    }
}
