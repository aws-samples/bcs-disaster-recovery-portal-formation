#!/bin/bash

# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

# This script can deploy the resources to one AWS account, update them and finally destroy them.

readonly DR="DRPortal-"
readonly CE="CloudEndure"
readonly DbD="DbDump"
readonly DbR="DbReplica"
readonly ASSET_DIR="/tmp/drportal"
readonly S3_DIR="${ASSET_DIR}/s3"

declare action="deploy"
declare copy="true"
declare component="all"
declare image="all"
declare system="all"

declare bucket

function usage() {
  echo "Usage:"
  echo "./deploy.sh [-a deploy|destroy|update]"
  echo "            [-p false|true]"
  echo "            [-i all|none|s3|dbdump.mysql|dynamo]"
  echo "            [-s all|vpc|ce|cem|s3|dbdump.mysql|dbreplica.oracle|dynamo]"
  echo "            [-c all|vpc|bucket|db"
  echo "               |common.f|common.api|common.deploy"
  echo "               |vpc.f|vpc.steps|vpc.deploy"
  echo "               |ce.base"
  echo "               |ce.f|ce.steps|ce.ssm|ce.deploy"
  echo "               |s3.f|s3.ecs|s3.steps|s3.deploy"
  echo "               |dynamo.f|dynamo.ecs|dynamo.steps|dynamo.deploy"
  echo "               |dbdump.mysql.f|dbdump.mysql.ecs|dbdump.mysql.steps"
  echo "               |dbreplica.oracle.f|dbreplica.oracle.steps"
  echo "               |site|client|server|route] "
  echo " -a the action to do, default deploy. update is to update lambda codes"
  echo " -p whether to copy assets to bucket or not, default true"
  echo " -i which image to publish, s3 or dynamo or all, default all"
  echo " -s which subsystems to deploy, default all"
  echo " -c the individual component to deploy, default all"
  echo
  echo "Set the following environment variables first:"
  echo " - AWS_ACCOUNT:    the 12-digit AWS account number"
  echo " - REGION:         the target region short name, such as cn-northwest-1"
  echo " - PROFILE:        the credential profile for target region"
  echo " - CE_TOKEN:       to talk to cloud endure service"
  exit 1
}

function check_env() {
  if [ -z ${AWS_ACCOUNT} ] || [ -z ${REGION} ] || [ -z ${PROFILE} ] || [ -z ${CE_TOKEN} ]; then
    usage
  fi

  mkdir -p ${ASSET_DIR}/log
}

function datetime_now() {
  echo $(date "+%Y-%m-%d %H:%M:%S - ")
}

function datetime_now_label() {
  echo $(date "+%Y-%m-%dT%H-%M-%S")
}

function file_log() {
  local -ri index=$1
  echo "${ASSET_DIR}/log/deploy-${REGION}-${index}.log"
}

function bucket() {
  if [ -z $bucket ]; then
    bucket=$(aws ssm get-parameters --names "/drportal/s3/bucket" --profile ${PROFILE} | jq -r .Parameters[0].Value)
  fi

  echo $bucket
}

function run() {
  local -r cmd=$1
  echo "$(datetime_now) $cmd"
  ($cmd 2>&1 >$(file_log 1)) >$(file_log 2)
  if (($? != 0)); then
    cat $(file_log 1)
    cat $(file_log 2)
    if [ $action == "deploy" ]; then
      exit $?
    fi
  fi
}

function bootstrap() {
  local -r region=$1
  local -r profile=$2

  run "cdk bootstrap aws://${AWS_ACCOUNT}/$region --profile $profile"
}

function copy_template() {
  run "aws s3 cp ${S3_DIR}/template/common-bucket.json s3://$(bucket)/template/common-bucket.json --profile ${PROFILE}"
  run "aws s3 cp ${S3_DIR}/template/common-vpc.json s3://$(bucket)/template/common-vpc.json --profile ${PROFILE}"
}

function copy_lambda_ce() {
  run "aws s3 cp ${S3_DIR}/lambda/ce.zip     s3://$(bucket)/lambda/ce.zip     --profile ${PROFILE}"
}

function copy_lambda_common() {
  run "aws s3 cp ${S3_DIR}/lambda/common.zip s3://$(bucket)/lambda/common.zip --profile ${PROFILE}"
}

function copy_lambda_vpc() {
  run "aws s3 cp ${S3_DIR}/lambda/vpc.zip    s3://$(bucket)/lambda/vpc.zip    --profile ${PROFILE}"
}

function copy_lambda_s3() {
  run "aws s3 cp ${S3_DIR}/lambda/s3.zip     s3://$(bucket)/lambda/s3.zip     --profile ${PROFILE}"
}

function copy_lambda_dynamo() {
  run "aws s3 cp ${S3_DIR}/lambda/dynamo.zip s3://$(bucket)/lambda/dynamo.zip --profile ${PROFILE}"
}

function copy_lambda_dbdump_mysql() {
  run "aws s3 cp ${S3_DIR}/lambda/dbdump/mysql.zip s3://$(bucket)/lambda/dbdump/mysql.zip --profile ${PROFILE}"
}

function copy_lambda_dbreplica_oracle() {
  run "aws s3 cp ${S3_DIR}/lambda/dbreplica/oracle.zip s3://$(bucket)/lambda/dbreplica/oracle.zip --profile ${PROFILE}"
}

function copy_client() {
  run "aws s3 cp ${S3_DIR}/web/ROOT.war s3://$(bucket)/web/ROOT.war --profile ${PROFILE}"
}

function copy_server() {
  run "aws s3 cp ${S3_DIR}/web/server.jar s3://$(bucket)/web/server.jar --profile ${PROFILE}"
}

function copy_assets() {
  copy_template
  copy_lambda_common

  if [ $system == "all" ] || [ $system == "vpc" ]; then
    copy_lambda_vpc
  fi

  if [ $system == "all" ] || [ $system == "vpc" ] || [ $system == "ce" ] || [ $system == "cem" ]; then
    copy_lambda_ce
    copy_lambda_vpc
  fi

  if [ $system == "all" ] || [ $system == "s3" ]; then
    copy_lambda_s3
  fi

  if [ $system == "all" ] || [ $system == "dynamo" ]; then
    copy_lambda_dynamo
  fi

  if [ $system == "all" ] || [ $system == "dbdump.mysql" ]; then
    copy_lambda_dbdump_mysql
  fi

  if [ $system == "all" ] || [ $system == "dbreplica.oracle" ]; then
    copy_lambda_dbreplica_oracle
  fi

  copy_client
  copy_server
}

function publish_image() {
  local -r REPO_NAME=$1
  local -r respository=$(aws ecr describe-repositories --repository-names ${REPO_NAME} --profile ${PROFILE} | jq -r .repositories[0].repositoryUri)

  # unable to be run by run function and we do not want to print password
  aws ecr get-login-password --profile ${PROFILE} | sudo docker login --username AWS --password-stdin $respository >$(file_log 1)
  run "sudo docker tag $REPO_NAME $respository"
  run "sudo docker push $respository"
}

function deploy_base() {
  if [ $component == "all" ] || [ $component == "vpc" ]; then
    run "cdk deploy ${DR}Vpc --require-approval never --profile ${PROFILE}"
  fi

  if [ $component == "all" ] || [ $component == "bucket" ]; then
    run "cdk deploy ${DR}Bucket --require-approval never --profile ${PROFILE}"
    $copy && copy_assets || echo "Assets copying is skipped."
  fi

  if [ $component == "all" ] || [ $component == "db" ]; then
    run "cdk deploy ${DR}DynamoDb --require-approval never --profile ${PROFILE}"
  fi
}

function deploy_common() {
  if [ $component == "common.f" ]; then
    run "cdk deploy ${DR}Common-Lambda --require-approval never --profile ${PROFILE}"
  fi

  if [ $component == "all" ] || [ $component == "common.api" ]; then
    run "cdk deploy ${DR}Common-Api --require-approval never --profile ${PROFILE}"
  fi

  if [ $component == "common.deploy" ]; then
    run "cdk deploy ${DR}Common-CodeDeploy --require-approval never --profile ${PROFILE}"
  fi
}

function deploy_s3() {
  if [ $component == "s3.f" ]; then
    run "cdk deploy ${DR}S3-Lambda --require-approval never --profile ${PROFILE}"
  fi

  if [ $component == "s3.deploy" ]; then
    run "cdk deploy ${DR}S3-CodeDeploy --require-approval never --profile ${PROFILE}"
  fi

  if [ $component == "s3.ecs" ]; then
    run "cdk deploy ${DR}S3-Ecs --require-approval never --profile ${PROFILE}"
    [[ $image != "none" && ($image == "all" || $image == "s3") ]] && publish_image "drportal/s3/replicate-bucket" || echo "S3 image publishing is skipped."
  fi

  if [ $component == "all" ] || [ $component == "s3.steps" ]; then
    run "cdk deploy ${DR}S3-Steps --require-approval never --profile ${PROFILE}"
    [[ $image != "none" && ($image == "all" || $image == "s3") ]] && publish_image "drportal/s3/replicate-bucket" || echo "S3 image publishing is skipped."
  fi
}

function deploy_dynamo() {
  if [ $component == "dynamo.f" ]; then
    run "cdk deploy ${DR}Dynamo-Lambda --require-approval never --profile ${PROFILE}"
  fi

  if [ $component == "dynamo.deploy" ]; then
    run "cdk deploy ${DR}Dynamo-CodeDeploy --require-approval never --profile ${PROFILE}"
  fi

  if [ $component == "dynamo.ecs" ]; then
    run "cdk deploy ${DR}Dynamo-Ecs --require-approval never --profile ${PROFILE}"
    [[ $image != "none" && ($image == "all" || $image == "dynamo") ]] && publish_image "drportal/dynamo/replicate-table" || echo "Dynamo image publishing is skipped."
  fi

  if [ $component == "all" ] || [ $component == "dynamo.steps" ]; then
    run "cdk deploy ${DR}Dynamo-Steps --require-approval never --profile ${PROFILE}"
    [[ $image != "none" && ($image == "all" || $image == "dynamo") ]] && publish_image "drportal/dynamo/replicate-table" || echo "Dynamo image publishing is skipped."
  fi
}

function deploy_dbdump_mysql() {
  if [ $component == "dbdump.mysql.f" ]; then
    run "cdk deploy ${DR}${DbD}-MySql-Lambda --require-approval never --profile ${PROFILE}"
  fi

  if [ $component == "dbdump.mysql.ecs" ]; then
    run "cdk deploy ${DR}${DbD}-MySql-Ecs --require-approval never --profile ${PROFILE}"
    [[ $image != "none" && ($image == "all" || $image == "dbdump.mysql") ]] && publish_image "drportal/dbdump/mysql/dump" || echo "DbDump MySql image publishing is skipped."
  fi

  if [ $component == "all" ] || [ $component == "dbdump.mysql.steps" ]; then
    run "cdk deploy ${DR}${DbD}-MySql-Steps --require-approval never --profile ${PROFILE}"
    [[ $image != "none" && ($image == "all" || $image == "dbdump.mysql") ]] && publish_image "drportal/dbdump/mysql/dump" || echo "DbDump MySql image publishing is skipped."
  fi
}

function deploy_dbreplica_oracle() {
  if [ $component == "dbreplica.oracle.f" ]; then
    run "cdk deploy ${DR}${DbR}-Oracle-Lambda --require-approval never --profile ${PROFILE}"
  fi

  if [ $component == "all" ] || [ $component == "dbreplica.oracle.steps" ]; then
    run "cdk deploy ${DR}${DbR}-Oracle-Steps --require-approval never --profile ${PROFILE}"
  fi
}

function deploy_vpc() {
  if [ $component == "vpc.f" ]; then
    run "cdk deploy ${DR}Vpc-Lambda --require-approval never --profile ${PROFILE}"
  fi

  if [ $component == "vpc.deploy" ]; then
    run "cdk deploy ${DR}Vpc-CodeDeploy --require-approval never --profile ${PROFILE}"
  fi

  if [ $component == "all" ] || [ $component == "vpc.steps" ]; then
    run "cdk deploy ${DR}Vpc-Steps --require-approval never --profile ${PROFILE}"
  fi
}

function deploy_ce() {
  if [ $component == "ce.f" ]; then
    run "cdk deploy ${DR}${CE}-Lambda --require-approval never --profile ${PROFILE}"
  fi

  if [ $component == "ce.deploy" ]; then
    run "cdk deploy ${DR}${CE}-CodeDeploy --require-approval never --profile ${PROFILE}"
  fi

  if [ $component == "all" ] || [ $component == "ce.ssm" ]; then
    run "cdk deploy ${DR}${CE}-Ssm --parameters ApiToken=${CE_TOKEN} --require-approval never --profile ${PROFILE}"
  fi

  if [ $component == "all" ] || [ $component == "ce.steps" ]; then
    run "cdk deploy ${DR}${CE}-Steps --require-approval never --profile ${PROFILE}"
  fi
}

function deploy_cem() {
  if [ $component == "all" ] || [ $component == "cem.base" ]; then
    run "cdk deploy ${DR}Cem --require-approval never --profile ${PROFILE}"
  fi
}

function deploy_site() {
  if [ $component == "all" ] || [ $component == "site" ]; then
    run "cdk deploy ${DR}Beanstalk --require-approval never --profile ${PROFILE}"
  fi
}

function deploy_route() {
  if [ $component == "all" ] || [ $component == "route" ]; then
    run "cdk deploy ${DR}Route --require-approval never --profile ${PROFILE}"
  fi
}

function deploy() {
  if [ $component == "all" ]; then
    bootstrap $REGION $PROFILE
  fi

  deploy_base
  deploy_common

  if [[ $system == "all" || $system == *"s3"* ]]; then
    deploy_s3
  fi

  if [[ $system == "all" || $system == *"dbdump.mysql"* ]]; then
    deploy_dbdump_mysql
  fi

  if [[ $system == "all" || $system == *"dbreplica.oracle"* ]]; then
    deploy_dbreplica_oracle
  fi

  if [[ $system == "all" || $system == *"dynamo"* ]]; then
    deploy_dynamo
  fi

  if [[ $system == "all" || $system == *"vpc"* ]]; then
    deploy_vpc
  fi

  if [[ $system == "all" || $system == *"ce"* ]]; then
    deploy_ce
  fi

  if [[ $system == "all" || $system == *"cem"* ]]; then
    deploy_cem
  fi

  deploy_site
  deploy_route
  run "echo 'bye.'"
}

function delete_repository() {
  local -r repo_name=$1
  run "aws ecr delete-repository --repository-name $repo_name --force --profile ${PROFILE}"
}

function destroy() {
  if [ $component == "all" ]; then
    run "cdk destroy ${DR}Route                --force --profile ${PROFILE}"
    run "cdk destroy ${DR}Beanstalk            --force --profile ${PROFILE}"

    run "cdk destroy ${DR}Vpc-Steps            --force --profile ${PROFILE}"
    run "cdk destroy ${DR}Vpc-CodeDeploy       --force --profile ${PROFILE}"
    run "cdk destroy ${DR}Vpc-Lambda           --force --profile ${PROFILE}"

    delete_repository "drportal/s3/replicate-bucket"
    run "cdk destroy ${DR}S3-Steps             --force --profile ${PROFILE}"
    run "cdk destroy ${DR}S3-Ecs               --force --profile ${PROFILE}"
    run "cdk destroy ${DR}S3-CodeDeploy        --force --profile ${PROFILE}"
    run "cdk destroy ${DR}S3-Lambda            --force --profile ${PROFILE}"

    run "cdk destroy ${DR}${CE}-Steps          --force --profile ${PROFILE}"
    run "cdk destroy ${DR}${CE}-CodeDeploy     --force --profile ${PROFILE}"
    run "cdk destroy ${DR}${CE}-Lambda         --force --profile ${PROFILE}"
    run "cdk destroy ${DR}${CE}-Ssm            --force --profile ${PROFILE}"

    run "cdk destroy ${DR}Cem                  --force --profile ${PROFILE}"

    delete_repository "drportal/dbdump/mysql/dump"
    run "cdk destroy ${DR}${DbD}-MySql-Steps   --force --profile ${PROFILE}"
    run "cdk destroy ${DR}${DbD}-MySql-Lambda  --force --profile ${PROFILE}"

    run "cdk destroy ${DR}${DbR}-Oracle-Steps  --force --profile ${PROFILE}"
    run "cdk destroy ${DR}${DbR}-Oracle-Lambda --force --profile ${PROFILE}"

    delete_repository "drportal/dynamo/replicate-table"
    run "cdk destroy ${DR}Dynamo-Steps         --force --profile ${PROFILE}"
    run "cdk destroy ${DR}Dynamo-Ecs           --force --profile ${PROFILE}"
    run "cdk destroy ${DR}Dynamo-CodeDeploy    --force --profile ${PROFILE}"
    run "cdk destroy ${DR}Dynamo-Lambda        --force --profile ${PROFILE}"
    run "cdk destroy ${DR}DynamoDb             --force --profile ${PROFILE}"

    run "cdk destroy ${DR}Common-CodeDeploy    --force --profile ${PROFILE}"
    run "cdk destroy ${DR}Common-Api           --force --profile ${PROFILE}"
    run "cdk destroy ${DR}Common-Lambda        --force --profile ${PROFILE}"

    run "aws s3 rm s3://$(bucket)          --recursive --profile ${PROFILE}"
    run "cdk destroy ${DR}Bucket               --force --profile ${PROFILE}"
    run "cdk destroy ${DR}Vpc                  --force --profile ${PROFILE}"
  fi
}

function publish_version() {
  local -r function_name=$1
  local -r profile=$2

  echo $(aws lambda publish-version --function-name ${function_name} --profile ${profile} | jq -r '.Version')
}

function update_function() {
  local -r function_name=$1
  local -r bucket_name=$2
  local -r key=$3
  local -r profile=$4

  run "aws lambda update-function-code
    --function-name ${function_name} --s3-bucket ${bucket_name} --s3-key ${key} --profile ${profile}"
  #  run "aws lambda update-alias
  #    --function-name ${function_name} --name prod --function-version $(publish_version ${function_name} ${profile})  --profile ${profile}"
}

function update() {
  local -r common_prefix="DRPCommon"
  local -ra common_names=("ApiFindProjectById" "ApiUpdateItemState" "AddPeerRoute" "DeletePeerRoute" "DeployCommonVpc" "PeerVpc" "UnpeerVpc" "DeleteProject" "FindProject" "FindCommonSubnet" "UpdateItemState")

  local -r vpc_prefix="DRPVpc"
  local -ra vpc_names=("AddVpcItem" "CheckVpcReplicated" "CreateVpcProject" "DeleteVpc" "UpdateTargetVpc" "ReplicateVpc" "ReplicateDhcp" "ReplicateSubnet" "ReplicateNetworkAcl" "ReplicateNatGateway" "ReplicateSecurityGroup" "ReplicateSecurityGroupRule" "ReplicateEgressIgw" "ReplicateIgw" "ReplicateRouteTable" "ReplicateEndpoint" "CheckWatchReady")

  local -r ce_prefix="DRP${CE}"
  local -ra ce_names=("CheckName" "CreateCredential" "CreateEndureProject" "CreatePortalProject" "ConfigureProject" "LinkConfig" "InstallAgent" "DeleteEndureProject" "DeployInstallAgentDocument" "ConfigureBlueprint" "LaunchMachines" "TerminateInstances" "PrepareProjectName")

  local -r s3_prefix="DRPS3"
  local -ra s3_names=("CheckBucketValid" "SetBucketAccelerate" "CreateStream" "DeleteStream" "DeleteDynamo" "ScanBucket" "ReplicateBucket")

  local -r dynamo_prefix="DRPDynamo"
  local -ra dynamo_names=("CheckSourceTable" "CheckTargetTable" "CheckStream" "ConfigureStream" "CheckSchema")

  local -r dbdump_mysql_prefix="DRP${DbD}MySql"
  local -ra dbdump_mysql_names=("CheckEnvironment" "PrepareEnvironment" "CallGetDatabases")

  local -r dbreplica_oracle_prefix="DRPDbReplicaOracle"
  local -ra dbreplica_oracle_names=("ChangeArchive" "ChangeNetwork" "ChangeParameters")

  if [ $component == "all" ] || [ $component == "common.f" ]; then
    copy_lambda_common
    for ((i = 0; i < ${#common_names[@]}; i++)); do
      update_function $common_prefix${common_names[$i]} $(bucket) "lambda/common.zip" $PROFILE
    done
  fi

  if [ $component == "all" ] || [ $component == "ce.f" ]; then
    copy_lambda_ce
    for ((i = 0; i < ${#ce_names[@]}; i++)); do
      update_function $ce_prefix${ce_names[$i]} $(bucket) "lambda/ce.zip" $PROFILE
    done
  fi

  if [ $component == "all" ] || [ $component == "vpc.f" ]; then
    copy_lambda_vpc
    for ((i = 0; i < ${#vpc_names[@]}; i++)); do
      update_function $vpc_prefix${vpc_names[$i]} $(bucket) "lambda/vpc.zip" $PROFILE
    done
  fi

  if [ $component == "all" ] || [ $component == "s3.f" ]; then
    copy_lambda_s3
    for ((i = 0; i < ${#s3_names[@]}; i++)); do
      update_function $s3_prefix${s3_names[$i]} $(bucket) "lambda/s3.zip" $PROFILE
    done
  fi

  if [ $component == "all" ] || [ $component == "dynamo.f" ]; then
    copy_lambda_dynamo
    for ((i = 0; i < ${#dynamo_names[@]}; i++)); do
      update_function $dynamo_prefix${dynamo_names[$i]} $(bucket) "lambda/dynamo.zip" $PROFILE
    done
  fi

  if [ $component == "all" ] || [ $component == "dbdump.mysql.f" ]; then
    copy_lambda_dbdump_mysql
    for ((i = 0; i < ${#dbdump_mysql_names[@]}; i++)); do
      update_function $dbdump_mysql_prefix${dbdump_mysql_names[$i]} $(bucket) "lambda/dbdump/mysql.zip" $PROFILE
    done
  fi

  if [ $component == "all" ] || [ $component == "dbreplica.oracle.f" ]; then
    copy_lambda_dbreplica_oracle
    for ((i = 0; i < ${#dbreplica_oracle_names[@]}; i++)); do
      update_function $dbreplica_oracle_prefix${dbreplica_oracle_names[$i]} $(bucket) "lambda/dbreplica/oracle.zip" $PROFILE
    done
  fi

  update_beanstalk
}

function get_application_name() {
  echo $(aws elasticbeanstalk describe-environments --profile $PROFILE | jq -r --arg name $1 '.Environments[] | select(.ApplicationName | contains($name)) | select(.Status == "Ready") | .ApplicationName')
}

function get_environment_id() {
  echo $(aws elasticbeanstalk describe-environments --profile $PROFILE | jq -r --arg name $1 '.Environments[] | select(.ApplicationName | contains($name)) | select(.Status == "Ready") | .EnvironmentId')
}

function update_beanstalk() {
  local -r label=$(datetime_now_label)
  local app_name
  local env_id

  if [ $component == "client" ]; then
    copy_client
    app_name=$(get_application_name "Client")
    env_id=$(get_environment_id "Client")
    run "aws elasticbeanstalk create-application-version
      --application-name $app_name
      --version-label $label
      --source-bundle S3Bucket=$(bucket),S3Key='web/ROOT.war'
      --profile $PROFILE"
  fi

  if [ $component == "server" ]; then
    copy_server
    app_name=$(get_application_name "Server")
    env_id=$(get_environment_id "Server")
    run "aws elasticbeanstalk create-application-version
      --application-name $app_name
      --version-label $label
      --source-bundle S3Bucket=$(bucket),S3Key='web/server.jar'
      --profile $PROFILE"
  fi

  if [ $component == "client" ] || [ $component == "server" ]; then
    run "aws elasticbeanstalk update-environment
    --application-name $app_name
    --environment-id $env_id
    --version-label $label
    --profile $PROFILE"
  fi
}

function deploy_main() {
  echo ""
  echo "Deploy project to AWS"
  check_env

  case $action in
  "destroy") destroy ;;
  "deploy") deploy ;;
  "update") update ;;
  esac
}

while getopts ":a:c:i:s:p:" option; do
  case $option in
  a)
    action=${OPTARG}
    [[ $action == "deploy" || $action == "destroy" || $action == "update" ]] || usage
    ;;
  c)
    component=${OPTARG}
    [[ $component == "all" || $component == "vpc" || $component == "bucket" || $component == "db" || $component == "common.f" || $component == "common.api" || $component == "common.deploy" || $component == "ce.f" || $component == "ce.deploy" || $component == "ce.steps" || $component == "ce.ssm" || $component == "cem.base" || $component == "vpc.f" || $component == "vpc.deploy" || $component == "vpc.steps" || $component == "s3.ecs" || $component == "s3.f" || $component == "s3.deploy" || $component == "s3.steps" || $component == "dynamo.ecs" || $component == "dynamo.f" || $component == "dynamo.deploy" || $component == "dynamo.steps" || $component == "dbdump.mysql.ecs" || $component == "dbdump.mysql.f" || $component == "dbdump.mysql.steps" || $component == "dbreplica.oracle.f" || $component == "dbreplica.oracle.steps" || $component == "site" || $component == "client" || $component == "server" || $component == "route" ]] || usage
    ;;
  i)
    image=${OPTARG}
    [[ $image == "all" || $image == "none" || $image == "s3" || $image == "dynamo" ]] || usage
    ;;
  s)
    system=${OPTARG}
    [[ $system == "all" || $system == "vpc" || $system == "ce" || $system == "cem" || $system == "s3" || $system == "dbdump.mysql" || $system == "dbreplica.oracle" || $system == "dynamo" ]] || usage
    ;;
  p)
    copy=${OPTARG}
    [[ $copy == "true" || $copy == "false" ]] || usage
    ;;
  *) usage ;;
  esac
done
shift $((OPTIND - 1))

deploy_main
