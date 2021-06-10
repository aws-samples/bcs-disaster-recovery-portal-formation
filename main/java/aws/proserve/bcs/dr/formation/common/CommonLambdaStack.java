// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.common;

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
public class CommonLambdaStack extends LambdaStack {

    @Inject
    CommonLambdaStack(App app, BucketStack bucketStack) {
        super(app, "DRPortal-Common-Lambda", bucketStack);

        setRole(Role.Builder
                .create(this, "Role")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AWSCloudFormationFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonS3ReadOnlyAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonDynamoDBFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMReadOnlyAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonVPCFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("SecretsManagerReadWrite")))
                .build());

        createFunction("project", "ApiFindProjectById");
        createFunction("project", "ApiUpdateItemState");
        createFunction("project", "DeleteProject");
        createFunction("project", "FindProject");
        createFunction("project", "UpdateItemState");
        createFunction("network", "AddPeerRoute");
        createFunction("network", "DeletePeerRoute");
        createFunction("network", "DeployCommonVpc");
        createFunction("network", "FindCommonSubnet");
        createFunction("network", "PeerVpc");
        createFunction("network", "UnpeerVpc");
    }

    private Function createFunction(String pkg, String label) {
        return createFunction(this, getRole(), label,
                "DRPCommon" + label,
                "aws.proserve.bcs.dr.lambda." + pkg + "." + label + "::handleRequest",
                "Common functions, created by DRPortal",
                getBucket(), S3Constants.LAMBDA_COMMON);
    }
}
