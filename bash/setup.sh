#!/bin/bash

# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

# This script can build and deploy to one AWS account in one go

readonly CDK_JAR_FILE="/tmp/ws/drpf/src/BCSDisasterRecoveryPortalFormation/build/BCSDisasterRecoveryPortalFormation-1.0-exe.jar"

function usage() {
  echo "Usage:"
  echo "./setup.sh input.json"
  exit 1
}

function setup_env() {
  local -r input=$1
  local -r account=$(cat $input | jq -r .account)
  local -r region=$(cat $input | jq -r .region)
  local -r access=$(cat $input | jq -r .access)
  local -r secret=$(cat $input | jq -r .secret)
  local -r ceToken=$(cat $input | jq -r .ce_token)
  local -r kinitToken=$(cat $input | jq -r .kinit_token)

  echo "Setup environmental variables"

  {
    echo $kinitToken
  } | kinit -f

  {
    echo $access
    echo $secret
    echo $region
    echo json
  } | aws configure --profile drportal

  export CDK_JAR=$CDK_JAR_FILE
  export AWS_ACCOUNT=$account
  export REGION=$region
  export PROFILE=drportal
  export CE_TOKEN=$ceToken
  export SERVER_URL=http://server.drportal.internal

  cp cdk.json $HOME/.cdk.json
}

function setup_main() {
  local -r numberOfArguments=$1
  local -r input=$2

  ((numberOfArguments == 0)) && usage
  setup_env $input

  ./build.sh

  if [ -f $CDK_JAR_FILE ]; then
    ./deploy.sh -a destroy
    ./deploy.sh
  fi
}

setup_main $# $1
