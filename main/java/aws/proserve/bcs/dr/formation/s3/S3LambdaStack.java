// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.s3;

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
public class S3LambdaStack extends LambdaStack {

    @Inject
    S3LambdaStack(App app, BucketStack bucketStack) {
        super(app, "DRPortal-S3-Lambda", bucketStack);

        setRole(Role.Builder
                .create(this, "Role")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonS3FullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonKinesisFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonDynamoDBFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("CloudWatchFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("SecretsManagerReadWrite")))
                .build());

        createFunction("CheckBucketValid");
        createFunction("SetBucketAccelerate");
        createFunction("CreateStream");
        createFunction("DeleteStream");
        createFunction("DeleteDynamo");
        createFunction("ScanBucket");
        createFunction("ReplicateBucket");
    }

    private Function createFunction(String label) {
        return createFunction(this, getRole(), label,
                "DRPS3" + label,
                String.format("aws.proserve.bcs.dr.s3.%s::handleRequest", label),
                String.format("Lambda to replicate S3 (%s), created by DRPortal", label),
                getBucket(), S3Constants.LAMBDA_S3);
    }
}
