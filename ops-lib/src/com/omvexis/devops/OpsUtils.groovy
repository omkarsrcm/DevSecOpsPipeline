#!/usr/bin/env groovy
package com.omvexis.devops

//Below Code is used to create dir inside a git repo mostly will be used for azure repo and k8s cluster
def dirCreation(dirName)
{
  sh 'pwd'
  sh "mkdir -p $dirName"
  sh 'ls -l'
}
def runResourceScript(workspace, resourcePath, option, args) {
    String request = libraryResource resourcePath
    env.fileName = workspace + "/script_" + System.currentTimeMillis() + ".sh"
    writeFile file: fileName, text: request
    sh "chmod +x ${fileName}"
    env.Option = option
    env.Args = args
    def cmd = fileName + " " + option + " " + args
    env.Return_Value = sh(script: 'sh ${fileName} ${Option} ${Args}', returnStdout: true)
    sh "rm -f ${fileName}"
}
def checkFileExists(path){
  if(!fileExists(path)){
    echo "File path is ${path}"
    error("unable to find file ${path}")
  }
}

def runResourcePythonScript(workspace, resourcePath, args) {
    String request = libraryResource resourcePath
    def fileName = workspace + "/script_" + System.currentTimeMillis() + ".py"
    writeFile file: fileName, text: request

    def cmd = "/usr/local/bin/python3.9 " + fileName + " " + args
    echo "Executing: ${cmd}"
    sh cmd
}


def runPythonScriptInPwd(fileName, args) {
    def cmd = "/usr/bin/python3.9 " + fileName + " " + args
    echo "Executing: ${cmd}"
    sh cmd
}

def checkoutServiceSource(gitRepo, gitBranch, gitCredId) {

    opsCheckout(this){
        branchName = gitBranch
        credentialsName = gitCredId
        githubRepositoryURL = "https://" + gitRepo
    }

    sh """
        pwd
        git checkout ${gitBranch}
    """

}

//Below function is used to connect a server to deploy docker containers.
/* app.conf,acrurl/servicename:tagno,app_lic_path,Remote_Host_No,Remote_Name,Remote_Cred_Id,AppConfigPath*/
//opsUtils.csDeployer("$ConfigAppRepo/${Service_Name}.conf","${Registry}/", "${Remote_Host_No}","${Remote_Name}","${Remote_Cred_Id}","${AppConfigPath}")

def csDeployer(appConfigFile,dockerImageName,remoteHost,remotePort,remoteName,remoteCredId,appName,appLicPath,appLogPath,acrLoginUrl,acrLoginCreds,dataDogPath,dataDogConfigPath,scriptName,dockerSocketPath,imagenames,timeStamp,appDLogpath)
{
  def remote = [:]
  remote.name = remoteName
  remote.host = remoteHost
  remote.allowAnyHosts = true
  remote.port = remotePort as Integer

  echo "############## Following App-Config-File=${appConfigFile},Docker-Image=${dockerImageName},App-Lic-Path=${appLicPath} will be used to perform deployment ##############"
  withCredentials([sshUserPrivateKey(credentialsId: "${remoteCredId}", keyFileVariable: 'identity', usernameVariable: 'userName',passphraseVariable: '')]) {
      remote.user = userName
      remote.identityFile = identity

      withCredentials([usernamePassword(credentialsId: "${acrLoginCreds}", passwordVariable: 'acrPass', usernameVariable: 'acrUser')])
      {
        echo "########### Login Into Azure-ACR for fetching the docker image ################"
        sshCommand remote: remote, sudo: true,command: "docker login ${acrLoginUrl} -u ${acrUser} -p ${acrPass}"
      }
  if (scriptName != '')
  {
     echo "######### Performing Deployment of $appName-scripts-container #######"
     sshCommand remote: remote, sudo: true,command: "docker ps -f status=exited -q | xargs docker rm || true"
     try {
        sshCommand remote: remote, sudo: true,command: "docker create -it --network host --name ${appName}-scriptRun -e EXECUTE_MIGRATION_SCRIPT='$scriptName' -v $appLogPath-container:$appLogPath -v $appLicPath:$appLicPath -v $dataDogConfigPath:$dataDogConfigPath -v $dataDogPath:$dataDogPath -v $appDLogPath:$appDLogPath $dockerImageName 1>&2"
      } catch(error) {
        // echo error.getClass().toString()
        echo error.getMessage().toString()
        sshCommand remote: remote, sudo: true,command: "docker container stop ${appName}-scriptRun || true"
        sshCommand remote: remote, sudo: true,command: "docker container rm ${appName}-scriptRun || true"
        sshCommand remote: remote, sudo: true,command: "docker create -it --network host --name ${appName}-scriptRun -e EXECUTE_MIGRATION_SCRIPT='$scriptName' -v $appLogPath-container:$appLogPath -v $appLicPath:$appLicPath -v $dataDogConfigPath:$dataDogConfigPath -v $dataDogPath:$dataDogPath -v $appDLogPath:$appDLogPath $dockerImageName 1>&2"
      }
      sshPut remote: remote, from: "${appName}", into: '/home/csomvexis'
     sshCommand remote: remote, sudo: true,command: "ls -lrt /home/csomvexis | grep ${appName}"
     sshCommand remote: remote, sudo: true,command: "tar c ${appName}/${appConfigFile} | docker cp - ${appName}-scriptRun:/etc/"
     sshRemove remote: remote, path: "/home/csomvexis/${appName}"
     //sh "tar c ${appName}/${appConfigFile} | ssh -i ${identity} -p ${remote.port} ${userName}@${remote.host} sudo docker cp - ${appName}-scriptRun:/etc/"
     sshCommand remote: remote, sudo: true,command: "docker start -a ${appName}-scriptRun"
  } else {
      echo "########### Performing Deployment Now,Docker Container Name would be based on the service name ie $appName ##############"
      if(imagenames == 'null')
      {
      env.PreviousDockerImageTagNo = sshCommand remote: remote, sudo: true,command: "docker inspect $appName | jq --raw-output '.[].Config.Image' || true "
      sshCommand remote: remote, sudo: true,command: "docker pull $dockerImageName || true"
      sshCommand remote: remote, sudo: true,command: "docker ps -f status=exited -q | xargs docker rm || true"

      try {
        sshCommand remote: remote, sudo: true,command: "docker create  --restart unless-stopped --network host --name ${appName}-New -v $appLogPath-container:$appLogPath -v $appLicPath:$appLicPath -v $dataDogConfigPath:$dataDogConfigPath -v $dataDogPath:$dataDogPath -v $appDLogPath:$appDLogPath $dockerImageName 1>&2"
      } catch(error) {
        echo error.getMessage().toString()
        sshCommand remote: remote, sudo: true,command: "docker container stop ${appName}-New || true"
        sshCommand remote: remote, sudo: true,command: "docker container rm ${appName}-New || true"
        sshCommand remote: remote, sudo: true,command: "docker create  --restart unless-stopped --network host --name ${appName}-New -v $appLogPath-container:$appLogPath -v $appLicPath:$appLicPath -v $dataDogConfigPath:$dataDogConfigPath -v $dataDogPath:$dataDogPath -v $appDLogPath:$appDLogPath $dockerImageName 1>&2"
      }
      sshPut remote: remote, from: "${appName}", into: '/home/csomvexis'
      sshCommand remote: remote, sudo: true,command: "ls -lrt /home/csomvexis | grep ${appName}"
      sshCommand remote: remote, sudo: true,command: "tar c ${appName}/${appConfigFile} | docker cp - ${appName}-New:/etc/"
      sshRemove remote: remote, path: "/home/csomvexis/${appName}"
      //sh "tar c ${appName}/${appConfigFile} | ssh -i ${identity} -p ${remote.port} ${userName}@${remote.host} sudo docker cp - ${appName}-New:/etc/"
      sshCommand remote: remote, sudo: true,command: "docker container stop $appName || true"
      sshCommand remote: remote, sudo: true,command: "docker container rm $appName || true"
      sshCommand remote: remote, sudo: true,command: "docker rename ${appName}-New ${appName} "
      sshCommand remote: remote, sudo: true,command: "docker start ${appName}"
      sshCommand remote: remote, sudo: true,command: "docker ps -f status=exited -q | xargs docker rm || true" 
      }
      else
      {
      env.PreviousDockerImageTagNo = sshCommand remote: remote, sudo: true,command: "docker inspect $appName | jq --raw-output '.[].Config.Image' || true "
      def imageNamesList = imageNames.split(',')
      imageNamesList.each { imageName ->
        sshCommand remote: remote, sudo: true, command: "docker pull ${env.Registry}/${imageName}:${timeStamp}"
      }
      sshCommand remote: remote, sudo: true,command: "docker pull $dockerImageName || true"
      sshCommand remote: remote, sudo: true,command: "docker ps -f status=exited -q | xargs docker rm || true"
      try {
        sshCommand remote: remote, sudo: true,command: "docker create  --restart unless-stopped --network host --name ${appName}-New -e BUILD_VERSION=${timeStamp} -v $appLogPath-container:$appLogPath -v $appLicPath:$appLicPath -v $dataDogConfigPath:$dataDogConfigPath -v $dataDogPath:$dataDogPath  -v $dockerSocketPath:$dockerSocketPath -v $appDLogPath:$appDLogPath $dockerImageName 1>&2"
      } catch(error) {
        echo error.getMessage().toString()
        sshCommand remote: remote, sudo: true,command: "docker container stop ${appName}-New || true"
        sshCommand remote: remote, sudo: true,command: "docker container rm ${appName}-New || true"
        sshCommand remote: remote, sudo: true,command: "docker create  --restart unless-stopped --network host --name ${appName}-New -e BUILD_VERSION=${timeStamp} -v $appLogPath-container:$appLogPath -v $appLicPath:$appLicPath -v $dataDogConfigPath:$dataDogConfigPath -v $dataDogPath:$dataDogPath  -v $dockerSocketPath:$dockerSocketPath -v $appDLogPath:$appDLogPath $dockerImageName 1>&2"
      }
      sshPut remote: remote, from: "${appName}", into: '/home/csomvexis'
      sshCommand remote: remote, sudo: true,command: "ls -lrt /home/csomvexis | grep ${appName}"
      sshCommand remote: remote, sudo: true,command: "tar c ${appName}/${appConfigFile} | docker cp - ${appName}-New:/etc/"
      sshRemove remote: remote, path: "/home/csomvexis/${appName}"
      //sh "tar c ${appName}/${appConfigFile} | ssh -i ${identity} -p ${remote.port} ${userName}@${remote.host} sudo docker cp - ${appName}-New:/etc/"
      sshCommand remote: remote, sudo: true,command: "docker container stop $appName || true"
      sshCommand remote: remote, sudo: true,command: "docker container rm $appName || true"
      sshCommand remote: remote, sudo: true,command: "docker rename ${appName}-New ${appName} "
      sshCommand remote: remote, sudo: true,command: "docker start ${appName}"
      sshCommand remote: remote, sudo: true,command: "docker ps -f status=exited -q | xargs docker rm || true"
      }
    }
  }
}

//Below command used to fetch docker image to get label section
def csDockerImageLabelFetch(RegistryUrl,RegistryCred,ServiceName,DockerBuildType,ClusterType,Tag_No)
{
        sh "docker pull ${RegistryUrl}/${ServiceName}_${DockerBuildType}_${ClusterType}:${Tag_No}"
        echo "######### Inprogess: Fetch Label ###########"
        env.EncryptedTemplate= sh(script: "docker inspect ${RegistryUrl}/${ServiceName}_${DockerBuildType}_${ClusterType}:${Tag_No} | jq -r '.[0].Config.Labels.cs_template'",returnStdout: true).trim()
        echo "########  Completed: Fetch Label #############"    
}


//Below Function is used to decode base64
def base64_decode(filename,templateconfname)
{
  //sh "base64 --decode ${filename} > ${templateconfname}"
  sh "set -x"
  sh "echo ${filename} | base64 --decode > ${templateconfname}"
  sh "set +x"
  sh "ls -l"
  sh "pwd"
}
// Encode files like template.conf

def base64_encode(filename)
{
  def encoded_file = sh(script: "cat ${filename}  | base64 -w 0", returnStdout: true).trim()
  return encoded_file
}

def yaml_dependency_repo_read(filename,reponame)
{
   env.Filename = filename
   echo "${Filename}"
   YamlContent = libraryResource("scripts/${Filename}")
   writeFile(file: "${Filename}", text: YamlContent)
   def configVal = readYaml file: "${Filename}"
   def repo_section=configVal["${reponame}"]
   return "${repo_section}"
   
}


// Used in service-builder
def dockerbuild(registryUrl,registryCred,dockerBuildType,dockerFile,repo_Name,timeStamp,labels,clusterType){
  git_commit = labels.cs_gitcommit
  encoded_conf = labels.cs_template
  docker_build_cmd = """docker build --progress plain -t ${registryUrl}/${repo_Name}_${dockerBuildType}_${clusterType}:${timeStamp} --pull --label cs_gitcommit=${git_commit} --label cs_template=${encoded_conf} -f ${dockerFile} . """
  
  sh """
       ${docker_build_cmd}
       docker push ${registryUrl}/${repo_Name}_${dockerBuildType}_${clusterType}:${timeStamp}
  """
}

// Used to login to container registry
def dockerLogin(registryURL,registryCredID){
   withCredentials([usernamePassword(credentialsId: "$registryCredID", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
        echo "###### Container registry login in-progress ######"
        sh "docker login ${registryURL} -u $USERNAME -p $PASSWORD"
   }
}

