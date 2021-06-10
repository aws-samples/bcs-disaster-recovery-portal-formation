// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.cem;

import aws.proserve.bcs.dr.cem.CemConstants;
import aws.proserve.bcs.dr.dynamo.DynamoConstants;
import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.RemovalPolicy;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.dynamodb.TableEncryption;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class CemStack extends Stack {

    @Inject
    CemStack(App app) {
        super(app, "DRPortal-Cem");

        Table.Builder
                .create(this, "BlueprintTable")
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .tableName(CemConstants.TABLE_BLUEPRINT)
                .partitionKey(Attribute.builder()
                        .name(DynamoConstants.KEY_ID)
                        .type(AttributeType.STRING)
                        .build())
                .sortKey(Attribute.builder()
                        .name(CemConstants.KEY_MACHINE_ID)
                        .type(AttributeType.STRING)
                        .build())
                .pointInTimeRecovery(true)
                .encryption(TableEncryption.AWS_MANAGED)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
    }
}
