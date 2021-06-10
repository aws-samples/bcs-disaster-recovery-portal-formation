#!/bin/bash

# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

# This script can pull source codes, build them and copy the built assets to the predefined folder.

readonly ASSET_DIR="/tmp/drportal"
readonly S3_DIR="${ASSET_DIR}/s3"
readonly DRP="BCSDisasterRecoveryPortal"
readonly C="Common"
readonly CE="CloudEndure"
readonly DB="DynamoDB"
readonly FN="Formation"
readonly L="Lambda"
readonly dev="development"

declare WS="/tmp/ws"
declare location="tmp"
declare action="all"
declare component="all"
declare image="true"

function usage() {
  echo "Usage:"
  echo "./build.sh [-l home|tmp]  "
  echo "           [-a all|pull|build|copy|s3]  "
  echo "           [-c all|cf|ce|common|dbdump.mysql|dynamo|s3|vpc|client|server]"
  echo "           [-i true|false]  "
  echo
  echo " -l location to pull source code, default to tmp."
  echo " -a the action to perform, default to all."
  echo " -c the component of job, default all. client means web client only."
  echo " -i build docker image or not, default to true."
  exit 1
}

function datetime_now() {
  echo $(date "+%Y-%m-%d %H:%M:%S - ")
}

function file_log() {
  local -ri index="$1"
  echo "${ASSET_DIR}/log/deploy-${index}.log"
}

function run() {
  local -r cmd=$1
  echo "$(datetime_now) $cmd"
  ($cmd 2>&1 >$(file_log 1)) >$(file_log 2)
  if (($? != 0)); then
    cat $(file_log 1)
    cat $(file_log 2)
    exit $?
  fi
}

function pull() {
  local -r dir=$1
  local -r vs=$2
  local -r pkg1=$3
  local -r pkg2=$4
  echo $pkg1

  if [ ! -d $WS/$dir ]; then
    mkdir -p $WS/$dir
    cd $WS/$dir || return
    run "brazil workspace create --root . --name ${dir} --versionSet ${vs}"
  fi

  run "brazil workspace use --package ${pkg1}"

  if [ ! -z $pkg2 ]; then
    run "brazil workspace use --package ${pkg2}"
  fi
}

function build() {
  local -r dir=$1
  local -r pkg=$2
  cd $WS/${dir}/src/$pkg 2>/dev/null || return
  echo $pkg
  brazil ws use --platform AL2012
  if [ $location != "tmp" ]; then
    run "brazil-build clean"
  fi
  run "brazil-build build"
}

function pull_cf() {
  pull drpf ${DRP}${FN}/${dev} ${DRP}${FN}
}

function build_cf() {
  echo Cloud${FN}
  build drpf ${DRP}${FN}
}

function copy_cf() {
  run "cdk ls"
  run "cp ${WS}/drpf/src/${DRP}${FN}/cdk.out/DRPortal-Bucket.template.json ${S3_DIR}/template/common-bucket.json"
  run "cp ${WS}/drpf/src/${DRP}${FN}/cdk.out/DRPortal-Vpc.template.json ${S3_DIR}/template/common-vpc.json"
  run "cp ${WS}/drpf/src/${DRP}${FN}/build/${DRP}${FN}-1.0-exe.jar ${S3_DIR}/cf.jar"
}

function pull_ce() {
  pull drpl ${DRP}${L}/${dev} ${DRP}${CE}
}

function build_ce() {
  echo ${CE}
  build drpl ${DRP}${CE}
}

function copy_ce() {
  run "cp ${WS}/drpl/src/${DRP}${CE}/build/${DRP}${CE}-1.0-lambda.zip ${S3_DIR}/lambda/ce.zip"
}

function pull_common() {
  pull drpl ${DRP}${L}/${dev} ${DRP}${L}
}

function build_common() {
  echo ${C}
  build drpl ${DRP}${L}
}

function copy_common() {
  run "cp ${WS}/drpl/src/${DRP}${L}/build/${DRP}${L}-1.0-lambda.zip ${S3_DIR}/lambda/common.zip"
}

function pull_dbdump_mysql() {
  pull drpl ${DRP}${L}/${dev} ${DRP}MySqlDump
}

function build_dbdump_mysql() {
  echo MySqlDump
  build drpl ${DRP}MySqlDump
  $image && run "sudo docker build --file DockerFile --tag drportal/dbdump/mysql/dump:latest build/lambda"
}

function copy_dbdump_mysql() {
  run "cp ${WS}/drpl/src/${DRP}MySqlDump/build/${DRP}MySqlDump-1.0-lambda.zip ${S3_DIR}/lambda/dbdump/mysql.zip"
}

function pull_dynamo() {
  pull drpl ${DRP}${L}/${dev} ${DRP}${DB} ${DRP}${DB}Stream
}

function build_dynamo() {
  echo ${DB}
  build drpl ${DRP}${DB}
  build drpl ${DRP}${DB}Stream
  $image && run "sudo docker build --file DockerFile --tag drportal/dynamo/replicate-table:latest build/lambda"
}

function copy_dynamo() {
  run "cp ${WS}/drpl/src/${DRP}${DB}/build/${DRP}${DB}-1.0-lambda.zip ${S3_DIR}/lambda/dynamo.zip"
}

function pull_s3() {
  pull drpl ${DRP}${L}/${dev} ${DRP}S3
}

function build_s3() {
  echo "S3"
  build drpl ${DRP}S3
  $image && run "sudo docker build --file DockerFile --tag drportal/s3/replicate-bucket:latest build/lambda"
}

function copy_s3() {
  run "cp ${WS}/drpl/src/${DRP}S3/build/${DRP}S3-1.0-lambda.zip ${S3_DIR}/lambda/s3.zip"
}

function pull_vpc() {
  pull drpl ${DRP}${L}/${dev} ${DRP}Vpc
}

function build_vpc() {
  echo "VPC"
  build drpl ${DRP}Vpc
}

function copy_vpc() {
  run "cp ${WS}/drpl/src/${DRP}Vpc/build/${DRP}Vpc-1.0-lambda.zip ${S3_DIR}/lambda/vpc.zip"
}

function pull_client() {
  pull drpc2 ${DRP}Client/${dev} ${DRP}Client
}

function build_client() {
  echo "Client"
  build drpc2 ${DRP}Client
}

function copy_client() {
  run "cp ${WS}/drpc2/src/${DRP}Client/build/webapps/ROOT.war ${S3_DIR}/web/ROOT.war"
}

function pull_server() {
  pull drps ${DRP}Server/${dev} ${DRP}Server
}

function build_server() {
  echo "Server"
  build drps ${DRP}Server
}

function copy_server() {
  run "cp ${WS}/drps/src/${DRP}Server/build/${DRP}Server-1.0-exe.jar ${S3_DIR}/web/server.jar"
}

function check_env() {
  docker --version >/dev/null 2>/dev/null
  if (($? != 0)); then
    echo "Warning: docker is not running."
  fi

  if [ -z $CDK_JAR ]; then
    echo "CDK_JAR: the jar file for cdk is not set."
    usage
  fi

  if [ -z $SERVER_URL ]; then
    echo "WARN: SERVER_URL is not set, use localhost."
  fi
}

function build_main() {
  echo ""
  echo "Build project"
  check_env

  if [ $action == "s3" ]; then
    run "aws s3 sync ${S3_DIR} s3://${BUCKET} --profile ${PROFILE}"
    return
  fi

  if [ $location == "tmp" ]; then
    echo "Use /tmp as the workspace place"
    WS=/tmp/ws
    rm -rf $WS || return
  else
    echo "Use $HOME/ws as the workspace place"
    WS=$HOME/ws
  fi

  mkdir -p ${ASSET_DIR}/log
  mkdir -p ${S3_DIR}/lambda
  mkdir -p ${S3_DIR}/lambda/dbdump
  mkdir -p ${S3_DIR}/template
  mkdir -p ${S3_DIR}/web
  mkdir -p $WS

  if [ $component == "all" ] || [ $component == "cf" ]; then
    [[ $action == "all" || $action == "pull" ]] && pull_cf
    [[ $action == "all" || $action == "build" ]] && build_cf
    [[ $action == "all" || $action == "copy" ]] && copy_cf
  fi

  if [ $component == "all" ] || [ $component == "common" ]; then
    [[ $action == "all" || $action == "pull" ]] && pull_common
    [[ $action == "all" || $action == "build" ]] && build_common
    [[ $action == "all" || $action == "copy" ]] && copy_common
  fi

  if [ $component == "all" ] || [ $component == "ce" ]; then
    [[ $action == "all" || $action == "pull" ]] && pull_ce
    [[ $action == "all" || $action == "build" ]] && build_ce
    [[ $action == "all" || $action == "copy" ]] && copy_ce
  fi

  if [ $component == "all" ] || [ $component == "dbdump.mysql" ]; then
    [[ $action == "all" || $action == "pull" ]] && pull_dbdump_mysql
    [[ $action == "all" || $action == "build" ]] && build_dbdump_mysql
    [[ $action == "all" || $action == "copy" ]] && copy_dbdump_mysql
  fi

  if [ $component == "all" ] || [ $component == "dynamo" ]; then
    [[ $action == "all" || $action == "pull" ]] && pull_dynamo
    [[ $action == "all" || $action == "build" ]] && build_dynamo
    [[ $action == "all" || $action == "copy" ]] && copy_dynamo
  fi

  if [ $component == "all" ] || [ $component == "s3" ]; then
    [[ $action == "all" || $action == "pull" ]] && pull_s3
    [[ $action == "all" || $action == "build" ]] && build_s3
    [[ $action == "all" || $action == "copy" ]] && copy_s3
  fi

  if [ $component == "all" ] || [ $component == "vpc" ]; then
    [[ $action == "all" || $action == "pull" ]] && pull_vpc
    [[ $action == "all" || $action == "build" ]] && build_vpc
    [[ $action == "all" || $action == "copy" ]] && copy_vpc
  fi

  if [ $component == "all" ] || [ $component == "client" ]; then
    [[ $action == "all" || $action == "pull" ]] && pull_client
    [[ $action == "all" || $action == "build" ]] && build_client
    [[ $action == "all" || $action == "copy" ]] && copy_client
  fi

  if [ $component == "all" ] || [ $component == "server" ]; then
    [[ $action == "all" || $action == "pull" ]] && pull_server
    [[ $action == "all" || $action == "build" ]] && build_server
    [[ $action == "all" || $action == "copy" ]] && copy_server
  fi
}

while getopts ":l:a:c:i:" option; do
  case $option in
  l)
    location=${OPTARG}
    [[ $location == "home" || $location == "tmp" ]] || usage
    ;;
  a)
    action=${OPTARG}
    [[ $action == "all" || $action == "pull" || $action == "build" || $action == "copy" || $action == "s3" ]] || usage
    ;;
  c)
    component=${OPTARG}
    [[ $component == "all" || $component == "cf" || $component == "ce" || $component == "common" || $component == "dbdump.mysql" || $component == "dynamo" || $component == "s3" || $component == "vpc" || $component == "client" || $component == "server" ]] || usage
    ;;
  i)
    image=${OPTARG}
    [[ $image == "true" || $image == "false" ]] || usage
    ;;
  *) usage ;;
  esac
done
shift $((OPTIND - 1))

build_main
