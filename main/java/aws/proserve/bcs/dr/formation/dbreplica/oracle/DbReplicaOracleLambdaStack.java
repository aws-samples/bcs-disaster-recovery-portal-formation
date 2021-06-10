// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.dbreplica.oracle;

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
public class DbReplicaOracleLambdaStack extends LambdaStack {

    @Inject
    DbReplicaOracleLambdaStack(App app, BucketStack bucketStack) {
        super(app, "DRPortal-DbReplica-Oracle-Lambda", bucketStack);

        setRole(Role.Builder
                .create(this, "Role")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("SecretsManagerReadWrite")))
                .build());

        createFunction("ChangeArchive");
        createFunction("ChangeNetwork");
        createFunction("ChangeParameters");
        createFunction("CheckDatabaseRole");
        createFunction("CreateControlFile");
        createFunction("CreatePwdFile");
        createFunction("GetDiskSpace");
        createFunction("GetOperatingSystem");
        createFunction("GetOracleVersion");
        createFunction("NewPrimaryRestart");
        createFunction("OldPrimaryStartSync");
        createFunction("PrimaryCheckAlert");
        createFunction("PrimarySwitch");
        createFunction("StandbyBuildByBackup");
        createFunction("StandbyBuildByDuplicate");
        createFunction("StandbyCheckAlert");
        createFunction("StandbyCheckGap");
        createFunction("StandbyCopyFileTo");
        createFunction("StandbyRestart");
        createFunction("StandbyStartSync");
        createFunction("StandbyStartup");
        createFunction("StandbyStopRedo");
        createFunction("StandbySwitch");
        createFunction("TestPing");
        createFunction("TestSync");
        createFunction("TestSyncAgain");
    }

    private Function createFunction(String label) {
        return createFunction(this, getRole(), label,
                "DRPDbReplicaOracle" + label,
                String.format("aws.proserve.bcs.dr.ce.%s::handleRequest", label),
                String.format("Lambda to manage Oracle DataGuard (%s), created by DRF", label),
                getBucket(), S3Constants.LAMBDA_DBREPLICA_ORACLE);
    }
}
