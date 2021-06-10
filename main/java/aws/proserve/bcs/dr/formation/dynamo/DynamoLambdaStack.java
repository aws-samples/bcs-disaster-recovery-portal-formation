// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.dynamo;

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
public class DynamoLambdaStack extends LambdaStack {

    @Inject
    DynamoLambdaStack(App app, BucketStack bucketStack) {
        super(app, "DRPortal-Dynamo-Lambda", bucketStack);

        setRole(Role.Builder
                .create(this, "Role")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonDynamoDBFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("SecretsManagerReadWrite")))
                .build());

        createFunction("CheckSourceTable");
        createFunction("CheckTargetTable");
        createFunction("CheckStream");
        createFunction("CheckSchema");
        createFunction("ConfigureStream");
    }

    private Function createFunction(String label) {
        return createFunction(this, getRole(), label,
                "DRPDynamo" + label,
                String.format("aws.proserve.bcs.dr.dynamo.%s::handleRequest", label),
                String.format("Lambda to replicate DynamoDB (%s), created by DRPortal", label),
                getBucket(), S3Constants.LAMBDA_DYNAMO);
    }
}
