// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.ce;

import aws.proserve.bcs.dr.ce.CloudEndureConstants;
import aws.proserve.bcs.dr.formation.Keys;
import org.yaml.snakeyaml.Yaml;
import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.CfnParameter;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.services.secretsmanager.CfnSecret;
import software.amazon.awscdk.services.ssm.CfnDocument;
import software.amazon.awscdk.services.ssm.StringParameter;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class CloudEndureSsmStack extends Stack {
    private static final String CE_AWS_ID_VALUE = "6849e59c-29f5-4e10-a459-9d8584c7524b";

    @Inject
    CloudEndureSsmStack(App app, Yaml yaml) {
        super(app, "DRPortal-CloudEndure-Ssm");

        final var stream = getClass().getClassLoader().getResourceAsStream("InstallCloudEndureAgent.yaml");
        CfnDocument.Builder
                .create(this, "InstallCloudEndureAgentDocument")
                .documentType("Command")
                .content(yaml.load(stream))
                .build();

        final var token = CfnParameter.Builder
                .create(this, "ApiToken")
                .type("String")
                .description("The API token provided by CloudEndure.")
                .build();

        CfnSecret.Builder
                .create(this, "Token")
                .name(CloudEndureConstants.PARAM_TOKEN)
                .secretString(token.getValueAsString())
                .build();

        var awsId = System.getenv(Keys.CLOUD_ENDURE_AWS_ID);
        awsId = awsId == null ? CE_AWS_ID_VALUE : awsId;
        StringParameter.Builder
                .create(this, "AwsCloudId")
                .parameterName(CloudEndureConstants.PARAM_AWS_CLOUD_ID)
                .stringValue(awsId)
                .build();
    }
}
