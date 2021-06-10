// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.dbdump.mysql;

import aws.proserve.bcs.dr.formation.s3.BucketStack;
import aws.proserve.bcs.dr.s3.S3Constants;
import aws.proserve.bcs.formation.LambdaStack;
import software.amazon.awscdk.core.App;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Function;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

@Singleton
public class DbDumpMySqlLambdaStack extends LambdaStack {

    @Inject
    DbDumpMySqlLambdaStack(App app, BucketStack bucketStack) {
        super(app, "DRPortal-DbDump-MySql-Lambda", bucketStack);

        setRole(Role.Builder
                .create(this, "Role")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaVPCAccessExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("IAMReadOnlyAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AWSCloudFormationFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AWSLambdaFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonS3ReadOnlyAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("SecretsManagerReadWrite")))
                .build());

        createFunction("CheckEnvironment");
        createFunction("PrepareEnvironment");
        createFunction("CallGetDatabases");
    }

    private Function createFunction(String label) {
        return createFunction(this, getRole(), label,
                "DRPDbDumpMySql" + label,
                String.format("aws.proserve.bcs.dr.dbdump.mysql.%s::handleRequest", label),
                String.format("Lambda to dump MySql/MariaDB (%s), created by DRPortal", label),
                getBucket(), S3Constants.LAMBDA_DBDUMP_MYSQL);
    }
}
