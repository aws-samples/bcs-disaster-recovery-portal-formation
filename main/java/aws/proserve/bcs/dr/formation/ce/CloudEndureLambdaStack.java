// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.ce;

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
public class CloudEndureLambdaStack extends LambdaStack {

    @Inject
    CloudEndureLambdaStack(App app, BucketStack bucketStack) {
        super(app, "DRPortal-CloudEndure-Lambda", bucketStack);

        setRole(Role.Builder
                .create(this, "Role")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonDynamoDBFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("SecretsManagerReadWrite")))
                .build());

        createFunction("CheckName");
        createFunction("CreateCredential");
        createFunction("CreateEndureProject");
        createFunction("CreatePortalProject");
        createFunction("ConfigureProject");
        createFunction("LinkConfig");
        createFunction("InstallAgent");
        createFunction("DeleteEndureProject");
        createFunction("DeployInstallAgentDocument");
        createFunction("ConfigureBlueprint");
        createFunction("LaunchMachines");
        createFunction("TerminateInstances");
        createFunction("PrepareProjectName");
    }

    private Function createFunction(String label) {
        return createFunction(this, getRole(), label,
                "DRPCloudEndure" + label,
                String.format("aws.proserve.bcs.dr.ce.%s::handleRequest", label),
                String.format("Lambda to manage CloudEndure (%s), created by DRPortal", label),
                getBucket(), S3Constants.LAMBDA_CE);
    }
}
