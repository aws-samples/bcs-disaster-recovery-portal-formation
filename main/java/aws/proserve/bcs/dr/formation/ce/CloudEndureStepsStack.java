// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.ce;

import aws.proserve.bcs.dr.formation.common.CommonLambdaStack;
import aws.proserve.bcs.dr.formation.vpc.VpcLambdaStack;
import aws.proserve.bcs.dr.formation.vpc.VpcStepsStack;
import aws.proserve.bcs.formation.StepsStack;
import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.Duration;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.stepfunctions.Chain;
import software.amazon.awscdk.services.stepfunctions.Choice;
import software.amazon.awscdk.services.stepfunctions.Condition;
import software.amazon.awscdk.services.stepfunctions.IChainable;
import software.amazon.awscdk.services.stepfunctions.IntegrationPattern;
import software.amazon.awscdk.services.stepfunctions.StateMachine;
import software.amazon.awscdk.services.stepfunctions.TaskInput;
import software.amazon.awscdk.services.stepfunctions.tasks.StepFunctionsStartExecution;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class CloudEndureStepsStack extends StepsStack {

    private int counter;

    @Inject
    CloudEndureStepsStack(
            App app,
            CommonLambdaStack commonLambdaStack,
            VpcLambdaStack vpcLambdaStack,
            VpcStepsStack vpcStepsStack,
            CloudEndureLambdaStack lambdaStack) {
        super(app, "DRPortal-CloudEndure-Steps");

        mergeFunctionMap(commonLambdaStack.getFunctionMap());
        mergeFunctionMap(vpcLambdaStack.getFunctionMap());
        mergeFunctionMap(lambdaStack.getFunctionMap());

        final var role = Role.Builder
                .create(this, "Role")
                .assumedBy(new ServicePrincipal("states.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaRole")))
                .build();

        // Without this cdk complains circular dependency between resources.
        final var role2 = Role.Builder
                .create(this, "Role2")
                .assumedBy(new ServicePrincipal("states.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaRole")))
                .build();

        final var createProject = StateMachine.Builder
                .create(this, "CreateProjectMachine")
                .stateMachineName("DRPCloudEndureCreateProjectMachine")
                .role(role)
                .timeout(Duration.minutes(5))
                .definition(createCloudEndureProject())
                .build();

        StateMachine.Builder
                .create(this, "DeleteProjectMachine")
                .stateMachineName("DRPCloudEndureDeleteProjectMachine")
                .role(role)
                .timeout(Duration.minutes(5))
                .definition(deleteCloudEndureProject())
                .build();

        StateMachine.Builder
                .create(this, "PrepareCutbackMachine")
                .stateMachineName("DRPCloudEndurePrepareCutbackMachine")
                .role(role2)
                .timeout(Duration.minutes(15))
                .definition(prepareCutback(createProject))
                .build();

        StateMachine.Builder
                .create(this, "RunWizardMachine")
                .stateMachineName("DRPCloudEndureRunWizardMachine")
                .role(role2)
                .timeout(Duration.hours(1))
                .definition(runWizard(createProject, vpcStepsStack.getReplicateVpcMachine()))
                .build();
    }

    private Choice nameNotUsed(IChainable next) {
        return new Choice(this, "IsNameUsed" + (counter++))
                .when(Condition.booleanEquals("$.nameUsed", true),
                        fail("NameUsedException" + (counter++)))
                .otherwise(next);
    }

    /**
     * @return the CloudEndure project ID
     */
    private Chain createCloudEndureProject() {
        return Chain.start(f("CheckName", "$.name", "$.nameUsed"))
                .next(nameNotUsed(fDiscard("DeployInstallAgentDocument", Input.deployInstallAgentDocument)
                        .next(f("CreateCredential", "$.sourceCredentialId", "$.credential"))
                        .next(f("CreateEndureProject", "$.cloudEndureItem", Input.createEndureProject))
                        .next(f("ConfigureProject", "$.replicationConfiguration", Input.configProject))
                        .next(f("LinkConfig", "$.cloudEndureItem", Input.linkConfig))
                        .next(f("CreatePortalProject"))));
    }

    private Chain deleteCloudEndureProject() {
        final var deleteProject = fInput("DeleteProject", "$.id");
        final var deleteCutover = fTaskDiscard("DeleteEndureProject", "DeleteCutoverProject",
                "$.result.project.cloudEndureProject.items[0].id");
        final var deleteCutback = fTaskDiscard("DeleteEndureProject", "DeleteCutbackProject",
                "$.result.project.cloudEndureProject.items[1].id");
        final var shouldDeletePeerRoute = shouldDeletePeerVpc(deleteProject);

        deleteCutback.next(deleteCutover).next(shouldDeletePeerRoute);
        return Chain.start(f("FindProject", "$.id", "$.result"))
                .next(new Choice(this, "itemSize")
                        .when(Condition.numberEquals("$.result.itemSize", 1), deleteCutover)
                        .when(Condition.numberEquals("$.result.itemSize", 2), deleteCutback)
                        .otherwise(shouldDeletePeerRoute));
    }

    private Chain prepareCutback(StateMachine createItemMachine) {
        final var input = new HashMap<String, Object>();
        input.put("AWS_STEP_FUNCTIONS_STARTED_BY_EXECUTION_ID.$", "$$.Execution.Id");
        input.put("projectId.$", "$.project.id"); // do not delete, to add an item instead of a new project
        input.put("name.$", "$.newName");
        input.put("publicNetwork.$", "$.project.cloudEndureProject.publicNetwork");
        input.put("sourceRegion.$", "$.project.targetRegion.name");
        input.put("targetRegion.$", "$.project.sourceRegion.name");
        input.put("stagingSubnetId.$", "$.stagingSubnetId");
        input.put("sourceVpcId.$", "$.project.cloudEndureProject.targetVpcId");
        input.put("targetVpcId.$", "$.project.cloudEndureProject.sourceVpcId");
        input.put("sourceInstanceType.$", "$.project.cloudEndureProject.targetInstanceType");
        input.put("targetInstanceType.$", "$.project.cloudEndureProject.sourceInstanceType");
        input.put("sourceCredentialId.$", "$.sourceCredentialId");

        final var createItemExecution = StepFunctionsStartExecution.Builder
                .create(this, "CreateCloudEndureItem")
                .stateMachine(createItemMachine)
                .integrationPattern(IntegrationPattern.RUN_JOB)
                .input(TaskInput.fromObject(input))
                .resultPath("$.projectId")
                .build();

        final var prepareProject = fInput("PrepareProjectName", "$.projectId")
                .next(fTask("FindCommonSubnet", "FindCommonSubnet" + (counter++),
                        "$", "$.stagingSubnetId", Input.findCommonSubnet))
                .next(createItemExecution);

        final var terminate = fDiscard("TerminateInstances").next(prepareProject);
        return Chain.start(new Choice(this, "ShouldTerminateInstances")
                .when(Condition.booleanEquals("$.terminate", true), terminate)
                .otherwise(prepareProject));
    }

    private Choice continuousVpcRequired(IChainable next) {
        return new Choice(this, "IsContinuousVpcRequired")
                .when(Condition.booleanEquals("$.continuous", true),
                        f("CheckWatchReady", "$.sourceRegion", "$.continuousReady")
                                .next(new Choice(this, "IsContinuousReady")
                                        .when(Condition.booleanEquals("$.continuousReady", true), next)
                                        .otherwise(fail("ContinuousVpcNotReadyException"))))
                .otherwise(next);
    }

    private Choice vpcReplicated(StateMachine replicateVpcMachine, IChainable next) {
        return new Choice(this, "IsVpcReplicated")
                .when(Condition.booleanEquals("$.vpcReplicated", true), next)
                .otherwise(f("CreateVpcProject", "$.vpcProjectId")
                        .next(f("AddVpcItem", "$.itemAdded", Input.addVpcItem))
                        .next(vpcItemAdded(replicateVpcMachine, next)));
    }

    private Choice vpcItemAdded(StateMachine replicateVpcMachine, IChainable next) {
        final var replicateVpc = StepFunctionsStartExecution.Builder
                .create(this, "ReplicateVpc")
                .stateMachine(replicateVpcMachine)
                .integrationPattern(IntegrationPattern.RUN_JOB)
                .input(TaskInput.fromObject(Input.replicateVpcItem))
                .resultPath("$.replicateVpcOutput")
                .build();

        return new Choice(this, "IsVpcItemAdded")
                .when(Condition.booleanEquals("$.itemAdded", true),
                        fTaskDiscard("UpdateItemState", "UpdateItemStateReplicatingTask", Input.updateItemState("REPLICATING"))
                                .next(replicateVpc)
                                .next(f("UpdateTargetVpc", "$.targetVpcId", Input.updateTargetVpc))
                                .next(fTaskDiscard("UpdateItemState", "UpdateItemStateReplicatedTask", Input.updateItemState("REPLICATED")))
                                .next(next))
                .otherwise(next);
    }

    private Choice shouldAddPeerVpc(IChainable next) {
        return new Choice(this, "ShouldAddPeerVpc")
                .when(Condition.booleanEquals("$.publicNetwork", true), next)
                .otherwise(fDiscard("PeerVpc")
                        .next(fDiscard("AddPeerRoute"))
                        .next(next));
    }

    private Choice shouldDeletePeerVpc(IChainable next) {
        final Map<String, Object> unpeerVpc = Map.of(
                "sourceVpcId.$", "$.result.project.cloudEndureProject.sourceVpcId",
                "sourceRegion.$", "$.result.project.sourceRegion.name",
                "targetRegion.$", "$.result.project.targetRegion.name",
                "projectId.$", "$.result.project.id");
        return new Choice(this, "ShouldDeletePeerVpc")
                .when(Condition.booleanEquals("$.result.project.cloudEndureProject.publicNetwork", true), next)
                .otherwise(fDiscard("DeletePeerRoute", unpeerVpc)
                        .next(fDiscard("UnpeerVpc", unpeerVpc))
                        .next(next));
    }

    private Chain runWizard(StateMachine createCloudEndureProjectMachine, StateMachine replicateVpcMachine) {
        final var input = new HashMap<String, Object>();
        input.put("AWS_STEP_FUNCTIONS_STARTED_BY_EXECUTION_ID.$", "$$.Execution.Id");
        input.put("name.$", "$.name");
        input.put("publicNetwork.$", "$.publicNetwork");
        input.put("sourceRegion.$", "$.sourceRegion");
        input.put("targetRegion.$", "$.targetRegion");
        input.put("stagingSubnetId.$", "$.stagingSubnetId");
        input.put("sourceVpcId.$", "$.sourceVpcId");
        input.put("targetVpcId.$", "$.targetVpcId");
        input.put("sourceInstanceType.$", "$.sourceInstanceType");
        input.put("targetInstanceType.$", "$.targetInstanceType");
        input.put("sourceCredentialId.$", "$.sourceCredentialId");

        final var createCloudEndureProject = StepFunctionsStartExecution.Builder
                .create(this, "CreateCloudEndureProject")
                .stateMachine(createCloudEndureProjectMachine)
                .integrationPattern(IntegrationPattern.RUN_JOB)
                .input(TaskInput.fromObject(input))
                .resultPath("$.createCloudEndureProjectOutput")
                .build();

        return Chain.start(fTask("CheckName", "CheckNameTask" + (counter++),
                "$.name", "$.nameUsed"))
                .next(nameNotUsed(continuousVpcRequired(
                        f("CheckVpcReplicated", "$.vpcReplicated", Input.checkVpcReplicated)
                                .next(vpcReplicated(replicateVpcMachine,
                                        f("FindCommonSubnet", "$.stagingSubnetId")
                                                .next(shouldAddPeerVpc(createCloudEndureProject
                                                        .next(f("InstallAgent", "$.agentInstalled", Input.installAgent)))))))));
    }

    private static class Input {
        private static final Map<String, Object> findCommonSubnet = Map.of(
                "projectId.$", "$.project.id",
                "publicNetwork.$", "$.project.cloudEndureProject.publicNetwork",
                "targetRegion.$", "$.project.sourceRegion.name");

        private static final Map<String, Object> addVpcItem = Map.of(
                "projectId.$", "$.vpcProjectId",
                "item", Map.of(
                        "sourceVpcId.$", "$.sourceVpcId",
                        "targetVpcId.$", "$.targetVpcId",
                        "continuous.$", "$.continuous",
                        "cidr.$", "$.cidr"));

        private static final Map<String, Object> checkVpcReplicated = Map.of(
                "source", Map.of(
                        "vpcId.$", "$.sourceVpcId",
                        "region.$", "$.sourceRegion"),
                "target", Map.of(
                        "region.$", "$.targetRegion"));

        private static final Map<String, Object> replicateVpcItem = Map.of(
                "AWS_STEP_FUNCTIONS_STARTED_BY_EXECUTION_ID.$", "$$.Execution.Id",
                "source", Map.of(
                        "vpcId.$", "$.sourceVpcId",
                        "region.$", "$.sourceRegion"),
                "target", Map.of(
                        "region.$", "$.targetRegion"),
                "cidr.$", "$.cidr",
                "continuous.$", "$.continuous");

        private static final Map<String, Object> updateTargetVpc = Map.of(
                "projectId.$", "$.vpcProjectId",
                "itemId.$", "$.sourceVpcId",
                "targetVpcId.$", "$.replicateVpcOutput.Output");

        private static Map<String, Object> updateItemState(String state) {
            return Map.of(
                    "component", "vpc",
                    "id.$", "$.vpcProjectId",
                    "itemId.$", "$.sourceVpcId",
                    "state", state);
        }

        private static final Map<String, Object> installAgent = Map.of(
                "side", "source",
                "projectId.$", "$.createCloudEndureProjectOutput.Output",
                "instanceIds.$", "$.instanceIds");

        private static final Map<String, Object> deployInstallAgentDocument = Map.of(
                "region.$", "$.sourceRegion",
                "secretId.$", "$.sourceCredentialId");

        private static final Map<String, Object> createEndureProject = Map.of(
                "name.$", "$.name",
                "credentialId.$", "$.credential.id");

        private static final Map<String, Object> configProject = Map.of(
                "projectId.$", "$.cloudEndureItem.id",
                "credentialId.$", "$.credential.id",
                "publicNetwork.$", "$.publicNetwork",
                "targetRegion.$", "$.targetRegion",
                "stagingSubnetId.$", "$.stagingSubnetId");

        private static final Map<String, Object> linkConfig = Map.of(
                "projectId.$", "$.cloudEndureItem.id",
                "credentialId.$", "$.credential.id",
                "configurationId.$", "$.replicationConfiguration.id",
                "sourceRegion.$", "$.sourceRegion");
    }
}
