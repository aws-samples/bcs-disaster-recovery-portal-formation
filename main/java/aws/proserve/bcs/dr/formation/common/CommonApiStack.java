// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.common;

import aws.proserve.bcs.dr.formation.vpc.VpcStack;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.RemovalPolicy;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.services.apigateway.AccessLogFormat;
import software.amazon.awscdk.services.apigateway.AuthorizationType;
import software.amazon.awscdk.services.apigateway.EndpointConfiguration;
import software.amazon.awscdk.services.apigateway.EndpointType;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.LogGroupLogDestination;
import software.amazon.awscdk.services.apigateway.MethodLoggingLevel;
import software.amazon.awscdk.services.apigateway.MethodOptions;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.apigateway.StageOptions;
import software.amazon.awscdk.services.iam.PolicyDocument;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Singleton
public class CommonApiStack extends Stack {

    @Inject
    CommonApiStack(
            App app,
            ObjectMapper objectMapper,
            VpcStack vpcStack,
            CommonLambdaStack lambdaStack) {
        super(app, "DRPortal-Common-Api");

        final var stage = "latest";
        final var functionMap = lambdaStack.getFunctionMap();

        final var stream = Objects.requireNonNull(getClass().getClassLoader()
                .getResourceAsStream("aws/proserve/bcs/dr/formation/common/CommonApiPolicy.json"));
        final var documentContent = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining(System.lineSeparator()));
        final Map<String, Object> policy;
        try {
            policy = objectMapper.readValue(String.format(documentContent, vpcStack.getVpc().getVpcId()), Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // EDGE type is not supported in China regions.
        final var commonApi = RestApi.Builder
                .create(this, "CommonApi")
                .restApiName("CommonApi")
                .endpointConfiguration(EndpointConfiguration.builder()
                        .types(List.of(EndpointType.PRIVATE))
                        .vpcEndpoints(List.of(vpcStack.getApiEndpoint()))
                        .build())
                .defaultMethodOptions(MethodOptions.builder()
                        .authorizationType(AuthorizationType.NONE)
                        .build())
                .deployOptions(StageOptions.builder()
                        .stageName(stage)
                        .accessLogFormat(AccessLogFormat.jsonWithStandardFields())
                        .accessLogDestination(new LogGroupLogDestination(
                                LogGroup.Builder.create(this, "ApiAccessLog")
                                        .logGroupName("/aws/api/common")
                                        .retention(RetentionDays.ONE_MONTH)
                                        .removalPolicy(RemovalPolicy.DESTROY)
                                        .build()))
                        .loggingLevel(MethodLoggingLevel.INFO)
                        .metricsEnabled(true)
                        .build())
                .policy(PolicyDocument.fromJson(policy))
                .build();

        final var project = commonApi.getRoot()
                .addResource("projects")
                .addResource("{id}");

        project.addMethod("GET",
                LambdaIntegration.Builder
                        .create(functionMap.get("ApiFindProjectById"))
                        .build());

        project.addResource("items")
                .addResource("{itemId}")
                .addMethod("PUT",
                        LambdaIntegration.Builder
                                .create(functionMap.get("ApiUpdateItemState"))
                                .build());
    }
}
