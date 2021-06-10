// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.dynamo;

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
public class DynamoDbStack extends Stack {

    @Inject
    DynamoDbStack(App app) {
        super(app, "DRPortal-DynamoDb");

        Table.Builder
                .create(this, "ProjectTable")
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .tableName(DynamoConstants.TABLE_PROJECT)
                .partitionKey(Attribute.builder()
                        .name(DynamoConstants.KEY_ID)
                        .type(AttributeType.STRING)
                        .build())
                .pointInTimeRecovery(true)
                .encryption(TableEncryption.AWS_MANAGED)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        Table.Builder
                .create(this, "VpcTable")
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .tableName(DynamoConstants.TABLE_VPC)
                .partitionKey(Attribute.builder()
                        .name(DynamoConstants.KEY_ID)
                        .type(AttributeType.STRING)
                        .build())
                .pointInTimeRecovery(true)
                .encryption(TableEncryption.AWS_MANAGED)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
    }
}
