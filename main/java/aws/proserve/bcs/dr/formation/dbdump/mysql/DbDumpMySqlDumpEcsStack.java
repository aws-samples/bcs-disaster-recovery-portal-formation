// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.dbdump.mysql;

import aws.proserve.bcs.dr.dbdump.DbDumpConstants;
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
import software.amazon.awscdk.services.ecs.LogDriver;
import software.amazon.awscdk.services.ecs.MountPoint;
import software.amazon.awscdk.services.ecs.TaskDefinition;
import software.amazon.awscdk.services.ecs.Volume;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.stepfunctions.JsonPath;
import software.amazon.awscdk.services.stepfunctions.tasks.EcsRunTask;
import software.amazon.awscdk.services.stepfunctions.tasks.TaskEnvironmentVariable;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

@Singleton
public class DbDumpMySqlDumpEcsStack extends Stack {
    public static final String CONTAINER_NAME = "DRPDbDumpMySqlDumpContainer";
    public static final String SHARED_VOLUME = "shared_volume";

    private EcsRunTask dumpTask;

    @Inject
    DbDumpMySqlDumpEcsStack(App app, VpcStack vpcStack) {
        super(app, "DRPortal-DbDump-MySql-Dump-Ecs");

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
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonS3FullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMReadOnlyAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AWSStepFunctionsFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("CloudWatchFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("SecretsManagerReadWrite")))
                .build();

        final var taskDefinition = TaskDefinition.Builder
                .create(this, "DumpTaskDefinition")
                .cpu("2048")
                .memoryMiB("4096")
                .compatibility(Compatibility.FARGATE)
                .executionRole(executionRole)
                .taskRole(taskRole)
                .volumes(List.of(Volume.builder().name(SHARED_VOLUME).build()))
                .build();

        final var imageUrl = System.getenv(Images.DBDUMP_MYSQL_DUMP_IMAGE.name());
        final var image = imageUrl == null
                ? ContainerImage.fromEcrRepository(
                Repository.Builder
                        .create(this, "Repository")
                        .repositoryName("drportal/dbdump/mysql/dump") // cannot start with /
                        .removalPolicy(RemovalPolicy.DESTROY)
                        .build(), "latest")
                : ContainerImage.fromRegistry(imageUrl);

        final var cluster = Cluster.Builder
                .create(this, "DumpCluster")
                .clusterName("DRPDbDumpMySqlDumpCluster")
                .vpc(vpcStack.getVpc())
                .build();
        final var container = taskDefinition.addContainer(CONTAINER_NAME, ContainerDefinitionOptions.builder()
                .image(image)
                .logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                        .streamPrefix("dbdump/mysql/dump")
                        .logGroup(LogGroup.Builder
                                .create(this, "DbDumpMySqlDumpLogGroup")
                                .logGroupName("/aws/ecs/dbdump/mysql/dump")
                                .retention(RetentionDays.ONE_WEEK)
                                .removalPolicy(RemovalPolicy.DESTROY)
                                .build())
                        .build()))
                .build());

        container.addMountPoints(MountPoint.builder()
                .readOnly(false)
                .sourceVolume(SHARED_VOLUME)
                .containerPath(DbDumpConstants.DBDUMP_FOLDER)
                .build());

//        this.dumpTask = EcsRunTask.Builder
//                .create(this, "Dump")
//                .cluster(cluster)
//                .assignPublicIp(true)
//                .taskDefinition(taskDefinition)
//                .securityGroups(List.of(vpcStack.getSecurityGroup()))
//                .integrationPattern(IntegrationPattern.WAIT_FOR_TASK_TOKEN)
//                .containerOverrides(List.of(ContainerOverride.builder()
//                        .containerDefinition(container)
//                        .environment(environment()).build()))
//                .launchTarget(EcsFargateLaunchTarget.Builder.create()
//                        .platformVersion(FargatePlatformVersion.VERSION1_4).build())
//                .build();
    }

    private List<TaskEnvironmentVariable> environment() {
        return List.of(
                env("region", JsonPath.stringAt("$.region")),
                env("db_id", JsonPath.stringAt("$.dbId")),
                env("project_id", JsonPath.stringAt("$.projectId")),
                env("host", JsonPath.stringAt("$.host")),
                env("port", JsonPath.stringAt("$.port")),
                env("username", JsonPath.stringAt("$.username")),
                env("password", JsonPath.stringAt("$.password")),
                env("task_token", JsonPath.getTaskToken()));
    }

    private TaskEnvironmentVariable env(String name, String value) {
        return TaskEnvironmentVariable.builder().name(name).value(value).build();
    }

    EcsRunTask getDumpTask() {
        return dumpTask;
    }
}
