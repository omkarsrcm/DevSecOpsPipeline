def call(body){
  def opsUtils = new com.omvexis.devops.OpsUtils()
// Load the YAML content from the file
def podConfig = libraryResource 'files/jenkins-k8s-files/jenkins-k8s-agent-files/default-agents-pod.yaml'
/* This Pipeline is deploying for omvexis Docker Based Application. */
  pipeline{
     agent{
        kubernetes {
          // Use the pod configuration loaded from YAML file
          yaml podConfig
          customWorkspace "/home/jenkins/agent/${JOB_NAME}/${Cluster_Type}/"
          retries 5
        }
     }
     parameters{
          choice(choices: ['commercial','federal'], description: 'Please select Federal for ironbank images,otherwise leave it at the default option', name: 'Docker_Build_Type')   
          string (defaultValue: '', description: 'Please Provide Image Tag No,You would like to deploy', name: 'Tag_No', trim: true)
          choice(choices: ['hfnlife_dev', 'hfnlife_prd'], description: 'Please select the cluster', name: 'Cluster_Type')
          string (defaultValue: '', description: '[Optional] Comma separated list of scripts that has to be executed. Full path has to be specified', name: 'Script_Name', trim: true)
          choice(choices: ['hfnlife-ui', 'hfnlife_products','hfnlife_orders','hfnlife_user'], description: 'Please select the service to be deploy', name: 'Service_Name')

     }
     environment{
        Azure_URL = 'dev.azure.com/omkarkulkarni440128/omvexis/_git'
        AppFolder = 'app_config'  /* Using this incase template.config would be under app-config folder in app repo*/
        MainConfigFileName = "omvexis_config.yaml" /* This name is name of main app config file in cumulus-config  */
        TemplateConfigName = 'template.conf'
        EncryptedTemplateConfigName = "encryptedTeamplet.conf"
        ConfigAppRepo = "hfnlife_cumulus_config"
        CumulusConfigID= "f0cbc17c-5648-45d0-b972-b51ee823af7e"
        OpsPipelineRepoID= "aa52441d-8995-482b-9aad-e82f8c5b6533"
        DockerSocketPath = "/var/run/docker.sock"
        AppDConfigPath = "/opt/appdynamics/config"
        ImageRepo = "omkarsrcm"
        HelmRepoUrl = "oci://registry-1.docker.io/omkarsrcm"
     }
     stages{
         stage('User Validation') {
            when { expression { params.Cluster_Type == 'prd-india' || params.Cluster_Type == 'qa' }}
            steps {
              cleanWs()
              script {
                wrap([$class: 'BuildUser']) {
                  //env.Userid = "\"$env.BUILD_USER_ID\""
                  env.Userid = "\"$env.BUILD_USER_ID\"".toLowerCase()   
                  echo "${Userid} triggered the pipeline."
                  withCredentials([usernamePassword(credentialsId: "jenkins_ops_lib", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]){
                    sh 'curl -u $USERNAME:$PASSWORD -X GET "https://dev.azure.com/omkarkulkarni440128/omvexis/_apis/git/repositories/${OpsPipelineRepoID}/items?path=/resources/files/AuthorizedUserList.yaml&download=true&versionDescriptor.versionType=branch&version=main&api-version=6.1-preview.1" > AuthorizedUserList.yaml'
                    env.AuthorizedUsername = sh(script: "yq '.\"'\'$params.Cluster_Type\"'\'[] | select(. == $Userid)' AuthorizedUserList.yaml", returnStdout: true).trim()
                    echo "####### ${AuthorizedUsername} found in AuthorizedUserList file ######"
                    if (env.Userid != env.AuthorizedUsername)
                    {
                        error("####### ${Userid} not authorized for $Cluster_Type. Contact TL/Manager #######")
                    } else {
                       echo "####### ${Userid} authorized to run pipeline for $Cluster_Type Type  #######"
                    }
                  }
                }
              }
            }
          }
          stage('Pipeline Config:'){
            parallel{
              stage("Initialize: Registry Config"){
                steps{
                   script{
                            currentBuild.displayName = "${params.Service_Name}-${params.Cluster_Type}-${BUILD_NUMBER}"
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
                         }
                  }
              }
              stage("Initialize: Fetch template.conf from image label"){
                  steps{
                      script{
                          
                          sh "sleep 20"
                          opsUtils.dockerLogin("${Default_RegUrl}","${Registry_Cred}")

                          echo "########## Application_Name=$Service_Name,Version_No=$Tag_No,Env_Type=$Cluster_Type,docker_build_type=$Docker_Build_Type ################"
                          opsUtils.csDockerImageLabelFetch("${RegistryUrl}","${Registry_Cred}","${Service_Name}","${Docker_Build_Type}","${Cluster_Type}","${Tag_No}")

                          echo "######### Inprogres: Decrypt EncryptedTemplate.conf #########"
                          opsUtils.base64_decode("${EncryptedTemplate}","${TemplateConfigName}")
                          echo "######## Completed: Decrypt EncryptedTemplate.confd ########### "
                          
                          echo "######## App_Config_Filename:  ${Service_Name}.conf ########"
                          env.ServiceConfigFileName = "${Service_Name}.conf"
                      }
                   }
                }
            }

          } 

         stage("Initialize: Create App Config File"){
           steps{
              script{

                    echo "########### Download: central omvexis_config.yaml for actual config parameters ################"

                    withCredentials([usernamePassword(credentialsId: "jenkins_ops_lib", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]){
                       sh 'curl -u $USERNAME:$PASSWORD -X GET "https://dev.azure.com/omkarkulkarni440128/omvexis/_apis/git/repositories/${CumulusConfigID}/items?path=/omvexis_config.yaml&download=true&versionDescriptor.versionType=branch&version=${Cluster_Type}&api-version=6.1-preview.1" > omvexis_config.yaml'
                    }
                    echo "########## PWD  #############"
                    sh "pwd"
                    sh "ls -l"

                    echo "######### Convert: omvexis_config.yaml to omvexis_config.json, Fetch omvexis_config.yaml into variable ##############"
                    env.MainAppConfigFile = sh(script: 'ls  | grep "$MainConfigFileName" | sed s/.yaml// ', returnStdout: true).trim()
                    String argsvarone = "${MainAppConfigFile}.yaml ${MainAppConfigFile}.json"
                    opsUtils.runResourcePythonScript("${env.WORKSPACE}","scripts/yaml-to-json.py", argsvarone)
                    echo "######### omvexis_config.yaml converted to omvexis_config.json ##############"
                    sh "ls -l"

                    echo "############# Inprogess:  ${ServiceConfigFileName} file creation,with actual config ###############"
                    String argsvartwo = "${MainAppConfigFile}.json . ${TemplateConfigName} ${ServiceConfigFileName}"
                    opsUtils.runResourcePythonScript("${env.WORKSPACE}","scripts/jinjatemplate_old.py", argsvartwo)
                    
                    echo "############# Completed: Actual ${ServiceConfigFileName} file creation ###############"
                    sh "ls -l | grep ${ServiceConfigFileName}"
                    sh "pwd"
                        
              } 
           }
         }
         stage("Initialize: Helm Chart Creation")
         {
           steps
           {
             script
             {
                  echo "##### Inprogress: ${Service_Name}-${Docker_Build_Type}-${Cluster_Type}:${Tag_No} Chart Creation "
                  
                  withCredentials([usernamePassword(credentialsId: 'jenkins_ops_lib', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                    echo "###### Cloned: Central hfnlife_k8s repo ######"
                    sh """
                        git clone https://$USERNAME:$PASSWORD@${Azure_URL}/hfnlife_k8s
                        ls -l
                       """
                  }
                  container('yq') {
                     dir("${env.WORKSPACE}/hfnlife_k8s/${Service_Name}"){

                      sh "ls -l"
                      echo "###### Update chart.yaml ######"
                      sh """
                          yq eval -i '
                          .version = \"${params.Tag_No}\" |
                          .appVersion = \"${params.Tag_No}\"
                          ' Chart.yaml
                        """
                      echo "###### Update values.yaml ######"
                      sh """
                          yq eval -i '
                            .version = \"${params.Tag_No}\" |
                            .appVersion = \"${params.Tag_No}\" |
                            .image.tag = \"${params.Tag_No}\" |
                            .image.repository = \"${ImageRepo}\"
                          ' values.yaml
                        """
                      echo "##### Updating Config Map #####"
                      sh "mv ${env.WORKSPACE}/${ServiceConfigFileName} files/"

                      echo "##### Package helm chart #####"
                      
                     }
                  }
                     dir("${env.WORKSPACE}/hfnlife_k8s/"){
                        echo "##### Package helm chart #####"
                        sh "pwd"
                        sh "ls -l"
                        sh "helm package ${Service_Name} --version ${Tag_No}"
                        withCredentials([string(credentialsId: 'helmlogin', variable: 'helmlogin')]) {
                          sh "echo "${helmlogin}" | helm registry login registry-1.docker.io -u omkarsrcm --password-stdin"
                        }
                        sh "helm push ${Service_Name}-${Tag_No}.tgz oci://registry-1.docker.io/omkarsrcm"
                     }
              }
            }
          }
         stage("Performing Deployment")
         {
           steps
           {
             script
             {
                 // This env is passed so csDeployer function which has if condition that check when to run pre-scripts container vs actual app container
                 env.RunNoScript = ''
                 echo "########## Connecting to $Cluster_Type-App-Server Cluster to perform deployment ###############"
                 opsUtils.csDeployer("${Service_Name}","${Tag_No}","${HelmRepoUrl}","${Cluster_Type}")    
              }
            }
          }
      }
  }
}