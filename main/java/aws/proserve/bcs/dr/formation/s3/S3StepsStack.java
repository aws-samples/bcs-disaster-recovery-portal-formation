// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.s3;

import aws.proserve.bcs.formation.StepsStack;
import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.Duration;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.stepfunctions.Chain;
import software.amazon.awscdk.services.stepfunctions.Choice;
import software.amazon.awscdk.services.stepfunctions.Condition;
import software.amazon.awscdk.services.stepfunctions.Context;
import software.amazon.awscdk.services.stepfunctions.Parallel;
import software.amazon.awscdk.services.stepfunctions.StateMachine;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.Map;

@Singleton
public class S3StepsStack extends StepsStack {

    private final S3EcsStack ecsStack;

    @Inject
    S3StepsStack(
            App app,
            S3LambdaStack lambdaStack,
            S3EcsStack ecsStack) {
        super(app, "DRPortal-S3-Steps");
        this.ecsStack = ecsStack;

        mergeFunctionMap(lambdaStack.getFunctionMap());

        final var role = Role.Builder
                .create(this, "Role")
                .assumedBy(new ServicePrincipal("states.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaRole")))
                .build();

        StateMachine.Builder
                .create(this, "ReplicateBucketMachine")
                .stateMachineName("DRPS3ReplicateBucketMachine")
                .role(role)
                .timeout(Duration.days(7))
                .definition(replicateS3())
                .build();
    }

    private Chain replicateS3() {
        final var replicate = new Parallel(this, "SourcePreconditions").branch(
                f("CreateStream", "$.stream", Input.createStream),
                fDiscard("SetBucketAccelerate", Input.sourceBucket))
                .next(new Parallel(this, "ScanAndReplicate").branch(
                        fDiscard("ScanBucket", Input.scanBucket),
                        fargate("ReplicateBucket", ecsStack.getReplicateTask())))
                .next(new Parallel(this, "CleanUp").branch(
                        f("DeleteStream", Input.deleteStream),
                        f("DeleteDynamo", Input.deleteStream)));

        return Chain.start(fTask("CheckBucketValid",
                "CheckTargetBucketValidTask", "$.target.bucketValid", Input.targetBucket))
                .next(new Choice(this, "IsTargetBucketValid")
                        .when(isValid("target", false), fail("InvalidTargetBucket"))
                        .otherwise(fTask("CheckBucketValid",
                                "CheckSourceBucketValidTask", "$.source.bucketValid", Input.sourceBucket)
                                .next(new Choice(this, "IsSourceBucketValid")
                                        .when(isValid("source", false), fail("InvalidSourceBucket"))
                                        .otherwise(replicate))));
    }

    private Condition isValid(String bucket, boolean valid) {
        return Condition.booleanEquals("$." + bucket + ".bucketValid", valid);
    }

    private static class Input {
        private static final Map<String, Object> sourceBucket = Map.of(
                "projectId.$", "$.projectId",
                "bucket", Map.of(
                        "name.$", "$.source.bucket",
                        "region.$", "$.source.region"));

        private static final Map<String, Object> targetBucket = Map.of(
                "bucket", Map.of(
                        "name.$", "$.target.bucket",
                        "region.$", "$.target.region"));

        private static final Map<String, Object> createStream = Map.of(
                "bucket", Map.of(
                        "name.$", "$.target.bucket",
                        "region.$", "$.target.region"));

        private static final Map<String, Object> deleteStream = Map.of(
                "stream", Map.of(
                        "name.$", "$[0][0].stream",
                        "region.$", "$[0][0].target.region"));

        private static final Map<String, Object> replicateBucket = Map.of(
                "projectId.$", "$[0].projectId",
                "source", Map.of(
                        "name.$", "$[0].source.bucket",
                        "region.$", "$[0].source.region"),
                "target", Map.of(
                        "name.$", "$[0].target.bucket",
                        "region.$", "$[0].target.region"),
                "stream", Map.of(
                        "name.$", "$[0].stream",
                        "region.$", "$[0].target.region"));

        private static final Map<String, Object> scanBucket = Map.of(
                "projectId.$", "$[0].projectId",
                "bucket", Map.of(
                        "name.$", "$[0].source.bucket",
                        "region.$", "$[0].source.region"),
                "stream", Map.of(
                        "name.$", "$[0].stream",
                        "region.$", "$[0].target.region"));

        // TODO wait for TaskEnvironmentVariable.valuePath
        private static Map<String, Object> replicateBucketFargate() {
            return Map.of("Overrides",
                    Map.of("ContainerOverrides", List.of(
                            Map.of("Name", S3EcsStack.CONTAINER_NAME, "Environment", List.of(
                                    Map.of("Name", "source_bucket", "Value.$", "$[0].source.bucket"),
                                    Map.of("Name", "source_region", "Value.$", "$[0].source.region"),
                                    Map.of("Name", "target_bucket", "Value.$", "$[0].target.bucket"),
                                    Map.of("Name", "target_region", "Value.$", "$[0].target.region"),
                                    Map.of("Name", "stream_name", "Value.$", "$[0].stream"),
                                    Map.of("Name", "stream_region", "Value.$", "$[0].target.region"),
                                    Map.of("Name", "projectId", "Value.$", "$[0].projectId"),
                                    Map.of("Name", "task_token", "Value", Context.getTaskToken())))))
            );
        }
    }
}
