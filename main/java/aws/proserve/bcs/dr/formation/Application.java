// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation;

import aws.proserve.bcs.dr.formation.ce.CloudEndureDeployStack;
import aws.proserve.bcs.dr.formation.ce.CloudEndureLambdaStack;
import aws.proserve.bcs.dr.formation.ce.CloudEndureSsmStack;
import aws.proserve.bcs.dr.formation.ce.CloudEndureStepsStack;
import aws.proserve.bcs.dr.formation.cem.CemStack;
import aws.proserve.bcs.dr.formation.common.CommonApiStack;
import aws.proserve.bcs.dr.formation.common.CommonDeployStack;
import aws.proserve.bcs.dr.formation.common.CommonLambdaStack;
import aws.proserve.bcs.dr.formation.dbdump.mysql.DbDumpMySqlDumpEcsStack;
import aws.proserve.bcs.dr.formation.dbdump.mysql.DbDumpMySqlLambdaStack;
import aws.proserve.bcs.dr.formation.dbdump.mysql.DbDumpMySqlStepsStack;
import aws.proserve.bcs.dr.formation.dbreplica.oracle.DbReplicaOracleLambdaStack;
import aws.proserve.bcs.dr.formation.dbreplica.oracle.DbReplicaOracleStepsStack;
import aws.proserve.bcs.dr.formation.dynamo.DynamoDbStack;
import aws.proserve.bcs.dr.formation.dynamo.DynamoDeployStack;
import aws.proserve.bcs.dr.formation.dynamo.DynamoEcsStack;
import aws.proserve.bcs.dr.formation.dynamo.DynamoLambdaStack;
import aws.proserve.bcs.dr.formation.dynamo.DynamoStepsStack;
import aws.proserve.bcs.dr.formation.s3.BucketStack;
import aws.proserve.bcs.dr.formation.s3.S3DeployStack;
import aws.proserve.bcs.dr.formation.s3.S3EcsStack;
import aws.proserve.bcs.dr.formation.s3.S3LambdaStack;
import aws.proserve.bcs.dr.formation.s3.S3StepsStack;
import aws.proserve.bcs.dr.formation.site.BeanstalkStack;
import aws.proserve.bcs.dr.formation.vpc.RouteStack;
import aws.proserve.bcs.dr.formation.vpc.VpcDeployStack;
import aws.proserve.bcs.dr.formation.vpc.VpcLambdaStack;
import aws.proserve.bcs.dr.formation.vpc.VpcStack;
import aws.proserve.bcs.dr.formation.vpc.VpcStepsStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

public class Application {
    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        final var component = DaggerPortalComponent.builder().build();
        component.touch();

        final var assembly = component.app().synth();
        log.info("CloudAssembly has {} stacks", assembly.getStacks().size());
    }

    @Singleton
    static class Touch {
        @Inject
        Touch(
                BeanstalkStack beanstalkStack,
                CemStack cemStack,
                CommonApiStack commonApiStack,
                CommonLambdaStack commonLambdaStack,
                CommonDeployStack commonDeployStack,
                CloudEndureSsmStack cloudEndureSsmStack,
                CloudEndureLambdaStack cloudEndureLambdaStack,
                CloudEndureDeployStack cloudEndureDeployStack,
                CloudEndureStepsStack cloudEndureStepsStack,
                DbDumpMySqlDumpEcsStack dbDumpMySqlDumpEcsStack,
                DbDumpMySqlLambdaStack dbDumpMySqlLambdaStack,
                DbDumpMySqlStepsStack dbDumpMySqlStepsStack,
                DbReplicaOracleLambdaStack dbReplicaOracleLambdaStack,
                DbReplicaOracleStepsStack dbReplicaOracleStepsStack,
                DynamoDbStack dynamoDbStack,
                DynamoEcsStack dynamoEcsStack,
                DynamoLambdaStack dynamoLambdaStack,
                DynamoDeployStack dynamoDeployStack,
                DynamoStepsStack dynamoStepsStack,
                RouteStack routeStack,
                S3EcsStack s3EcsStack,
                S3LambdaStack s3LambdaStack,
                S3DeployStack s3DeployStack,
                S3StepsStack s3StepsStack,
                BucketStack bucketStack,
                VpcStack vpcStack,
                VpcLambdaStack vpcLambdaStack,
                VpcDeployStack vpcDeployStack,
                VpcStepsStack vpcStepsStack
        ) {
        }
    }
}
