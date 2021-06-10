// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.dynamo;

import aws.proserve.bcs.dr.formation.Images;
import aws.proserve.bcs.dr.formation.vpc.VpcStack;
import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.RemovalPolicy;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.AwsLogDriverProps;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.Compatibility;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.FargatePlatformVersion;
import software.amazon.awscdk.services.ecs.LogDriver;
import software.amazon.awscdk.services.ecs.TaskDefinition;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.stepfunctions.IntegrationPattern;
import software.amazon.awscdk.services.stepfunctions.JsonPath;
import software.amazon.awscdk.services.stepfunctions.tasks.ContainerOverride;
import software.amazon.awscdk.services.stepfunctions.tasks.EcsFargateLaunchTarget;
import software.amazon.awscdk.services.stepfunctions.tasks.EcsRunTask;
import software.amazon.awscdk.services.stepfunctions.tasks.TaskEnvironmentVariable;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

@Singleton
public class DynamoEcsStack extends Stack {
    public static final String CONTAINER_NAME = "DRPDynamoReplicateTableContainer";

    private final EcsRunTask replicateTask;

    @Inject
    DynamoEcsStack(App app, VpcStack vpcStack) {
        super(app, "DRPortal-Dynamo-Ecs");

        final var executionRole = Role.Builder
                .create(this, "ExecutionRole")
                .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AmazonECSTaskExecutionRolePolicy")))
                .build();

        final var taskRole = Role.Builder
                .create(this, "TaskRole")
                .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonKinesisFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonDynamoDBFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AWSStepFunctionsFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("CloudWatchFullAccess")))
                .build();

        final var cluster = Cluster.Builder
                .create(this, "ReplicateTableCluster")
                .clusterName("DRPDynamoReplicateTableCluster")
                .vpc(vpcStack.getVpc())
                .build();

        final var taskDefinition = TaskDefinition.Builder
                .create(this, "ReplicateTableTaskDefinition")
                .cpu("2048")
                .memoryMiB("4096")
                .compatibility(Compatibility.FARGATE)
                .executionRole(executionRole)
                .taskRole(taskRole)
                .build();

        final var imageUrl = System.getenv(Images.DYNAMO_IMAGE.name());
        final var image = imageUrl == null
                ? ContainerImage.fromEcrRepository(
                Repository.Builder
                        .create(this, "Repository")
                        .repositoryName("drportal/dynamo/replicate-table")
                        .removalPolicy(RemovalPolicy.DESTROY)
                        .imageScanOnPush(true)
                        .build(), "latest")
                : ContainerImage.fromRegistry(imageUrl);

        final var container = taskDefinition.addContainer(CONTAINER_NAME, ContainerDefinitionOptions.builder()
                .image(image)
                .logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                        .streamPrefix("dynamo")
                        .logGroup(LogGroup.Builder
                                .create(this, "DynamoLogGroup")
                                .logGroupName("/aws/ecs/dynamo/replicateTable")
                                .retention(RetentionDays.ONE_WEEK)
                                .removalPolicy(RemovalPolicy.DESTROY)
                                .build())
                        .build()))
                .build());

        this.replicateTask = EcsRunTask.Builder
                .create(this, "ReplicateTable")
                .cluster(cluster)
                .assignPublicIp(true)
                .taskDefinition(taskDefinition)
                .securityGroups(List.of(vpcStack.getSecurityGroup()))
                .integrationPattern(IntegrationPattern.WAIT_FOR_TASK_TOKEN)
                .containerOverrides(List.of(ContainerOverride.builder()
                        .containerDefinition(container)
                        .environment(environment()).build()))
                .launchTarget(EcsFargateLaunchTarget.Builder.create()
                        .platformVersion(FargatePlatformVersion.VERSION1_4).build())
                .build();
    }

    private List<TaskEnvironmentVariable> environment() {
        return List.of(
                env("source_table", JsonPath.stringAt("$[0].source.table")),
                env("source_region", JsonPath.stringAt("$[0].source.region")),
                env("target_table", JsonPath.stringAt("$[0].target.table")),
                env("target_region", JsonPath.stringAt("$[0].target.region")),
                env("project_id", JsonPath.stringAt("$[0].projectId")),
                env("task_token", JsonPath.getTaskToken()));
    }

    private TaskEnvironmentVariable env(String name, String value) {
        // TODO wait for TaskEnvironmentVariable.valuePath()
        return TaskEnvironmentVariable.builder().name(name).value(value).build();
    }

    EcsRunTask getReplicateTask() {
        return replicateTask;
    }
}
