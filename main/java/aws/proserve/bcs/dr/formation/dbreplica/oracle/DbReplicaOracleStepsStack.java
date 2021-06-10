// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.dbreplica.oracle;

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
import software.amazon.awscdk.services.stepfunctions.Succeed;
import software.amazon.awscdk.services.stepfunctions.tasks.StepFunctionsStartExecution;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

@Singleton
public class DbReplicaOracleStepsStack extends StepsStack {

    private int counter = 0;

    @Inject
    DbReplicaOracleStepsStack(App app, DbReplicaOracleLambdaStack lambdaStack) {
        super(app, "DRPortal-DbReplica-Oracle-Steps");
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

        final var checkEnv = StateMachine.Builder
                .create(this, "CheckEnvironment")
                .stateMachineName("DRPDbReplicaOracleCheckEnvironment")
                .role(role)
                .timeout(Duration.hours(1))
                .definition(checkEnvironment())
                .build();

        StateMachine.Builder
                .create(this, "BuildDataGuard")
                .stateMachineName("DRPDbReplicaOracleBuildDataGuard")
                .role(role2)
                .timeout(Duration.hours(1))
                .definition(buildDataGuard(checkEnv))
                .build();

        StateMachine.Builder
                .create(this, "Failover")
                .stateMachineName("DRPDbReplicaOracleFailover")
                .role(role)
                .timeout(Duration.hours(1))
                .definition(failover())
                .build();

        StateMachine.Builder
                .create(this, "Switchover")
                .stateMachineName("DRPDbReplicaOracleSwitchover")
                .role(role)
                .timeout(Duration.hours(1))
                .definition(switchover())
                .build();
    }

    private Chain checkEnvironment() {
        return Chain.start(f("GetOperatingSystem", "$.system"))
                .next(osSupported(f("GetOracleVersion", "$.oracleVersion")
                        .next(oracleSupported(f("GetDiskSpace", "$.diskSpace")
                                .next(diskSpaceEnough(Succeed.Builder
                                        .create(this, "EnvironmentAvailable").build()))))));
    }

    private Choice osSupported(IChainable supported) {
        return new Choice(this, "IsOperatingSystemSupported")
                .when(Condition.stringEquals("$.system", "Linux"), supported)
                .otherwise(fail("OperatingSystemNotSupported"));
    }

    private Choice oracleSupported(IChainable supported) {
        return new Choice(this, "IsOracleVersionSupported")
                .when(Condition.stringGreaterThan("$.oracleVersion", "11.2.0"), supported)
                .otherwise(fail("OracleVersionNotSupported"));
    }

    private Choice diskSpaceEnough(IChainable supported) {
        return new Choice(this, "IsDiskSpaceEnough")
                .when(Condition.numberGreaterThanEquals("$.diskSpace", 60), supported)
                .otherwise(fail("DiskSpaceNotEnough"));
    }

    private Chain buildDataGuard(StateMachine checkEnvMachine) {
        final var checkEnv = StepFunctionsStartExecution.Builder
                .create(this, "CheckEnv")
                .stateMachine(checkEnvMachine)
                .integrationPattern(IntegrationPattern.RUN_JOB)
                .outputPath("$.Output")
                .build();

        final var startSync = fDiscard("StandbyStartSync");
        return Chain.start(checkEnv)
                .next(fTask("CheckDatabaseRole", "CheckDatabaseRoleTask1", "$.role"))
                .next(fDiscard("ChangeParameters"))
                .next(fDiscard("ChangeArchive"))
                .next(fDiscard("ChangeNetwork"))
                .next(fDiscard("CreatePwdFile"))
                .next(fDiscard("CreateControlFile"))
                .next(fDiscard("StandbyCopyFileTo"))
                .next(fDiscard("StandbyStartup"))
                .next(fDiscard("TestPing"))
                .next(createMethod(fDiscard("StandbyBuildByBackup").next(startSync),
                        fDiscard("StandbyBuildByDuplicate").next(startSync)));
    }

    private Choice createMethod(IChainable duplicate, IChainable backup) {
        return new Choice(this, "CheckCreateMethod")
                .when(Condition.stringEquals("$.method", "DUPLICATE"), duplicate)
                .otherwise(backup);
    }

    private Chain failover() {
        return Chain.start(fTask("CheckDatabaseRole", "CheckDatabaseRoleTask2", "$.role"))
                .next(roleOk(fDiscard("StandbyCheckGap")
                        .next(fDiscard("StandbyStopRedo"))
                        .next(fTask("StandbyCheckAlert", "StandbyCheckAlertTask1", "$.standbyHasAlert"))
                        .next(noStandbyAlert(fTaskDiscard("StandbySwitch", "StandbySwitchTask1")
                                .next(fTask("StandbyCheckAlert", "StandbyCheckAlertAgainTask", "$.standbyHasAlertAgain"))
                                .next(noStandbyAlertAgain(fDiscard("NewPrimaryRestart")))))));
    }

    private Choice roleOk(IChainable next) {
        return Choice.Builder
                .create(this, "IfStandbyDatabaseRoleOk").build()
                .when(Condition.stringEquals("$.role", "toprimary"), next)
                .otherwise(fail("WrongStandbyDatabaseRoleException"));
    }

    private Chain switchover() {
        return Chain.start(f("TestSync", "$.syncing"))
                .next(syncing(fDiscard("PrimarySwitch")
                        .next(f("PrimaryCheckAlert", "$.primaryHasAlert"))
                        .next(noPrimaryAlert(fTaskDiscard("StandbySwitch", "StandbySwitchTask2")
                                .next(fTask("StandbyCheckAlert", "StandbyCheckAlertTask2", "$.standbyHasAlert"))
                                .next(noStandbyAlert(fDiscard("StandbyRestart")
                                        .next(fDiscard("OldPrimaryStartSync"))
                                        .next(f("TestSyncAgain", "$.syncAgain"))
                                        .next(syncingAgain(Succeed.Builder
                                                .create(this, "SwitchoverSucceeded").build()))))))));
    }

    private Choice syncing(IChainable next) {
        return new Choice(this, "IsSyncing")
                .when(Condition.booleanEquals("$.syncing", true), next)
                .otherwise(fail("TestSyncFailedException"));
    }

    private Choice syncingAgain(IChainable next) {
        return new Choice(this, "IsSyncingAgain")
                .when(Condition.booleanEquals("$.syncingAgain", true), next)
                .otherwise(fail("TestSyncFailedException"));
    }

    private Choice noPrimaryAlert(IChainable next) {
        return new Choice(this, "PrimaryHasAlert")
                .when(Condition.booleanEquals("$.primaryHasAlert", false), next)
                .otherwise(fail("PrimaryAlertException"));
    }

    private Choice noStandbyAlert(IChainable next) {
        return new Choice(this, "StandbyHasAlert" + (counter++))
                .when(Condition.booleanEquals("$.standbyHasAlert", false), next)
                .otherwise(fail("StandbyAlertException" + (counter++)));
    }

    private Choice noStandbyAlertAgain(IChainable next) {
        return new Choice(this, "StandbyHasAlertAgain")
                .when(Condition.booleanEquals("$.standbyHasAlertAgain", false), next)
                .otherwise(fail("StandbyAlertAgainException"));
    }

}
