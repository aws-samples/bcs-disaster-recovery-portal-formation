// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.ce;

import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.services.codedeploy.LambdaApplication;
import software.amazon.awscdk.services.codedeploy.LambdaDeploymentConfig;
import software.amazon.awscdk.services.codedeploy.LambdaDeploymentGroup;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Alias;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.List;

@Singleton
public class CloudEndureDeployStack extends Stack {

    @Inject
    CloudEndureDeployStack(App app, CloudEndureLambdaStack lambdaStack) {
        super(app, "DRPortal-CloudEndure-CodeDeploy");

        // cannot reuse the role, a bug in cdk, duplicated managed policies
        final var role = Role.Builder
                .create(this, "Role")
                .assumedBy(new ServicePrincipal("codedeploy.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSCodeDeployRoleForLambda")))
                .build();

        final var application = LambdaApplication.Builder
                .create(this, "LambdaApplication")
                .applicationName("DRPortal-CloudEndure-Lambda")
                .build();

        final var functionMap = lambdaStack.getFunctionMap();

        for (var entry : functionMap.entrySet()) {
            LambdaDeploymentGroup.Builder
                    .create(this, entry.getKey() + "DeploymentGroup")
                    // .role(role)
                    .deploymentGroupName(entry.getKey())
                    .application(application)
                    .deploymentConfig(LambdaDeploymentConfig.ALL_AT_ONCE)
                    .alias(Alias.Builder
                            .create(this, entry.getKey() + "ProdAlias")
                            .aliasName("prod")
                            .version(entry.getValue().addVersion(Instant.now().toString()))
                            .build())
                    .build();
        }
    }
}
