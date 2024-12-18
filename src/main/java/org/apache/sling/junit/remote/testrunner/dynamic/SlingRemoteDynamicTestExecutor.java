/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.junit.remote.testrunner.dynamic;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;

import java.io.StringReader;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;

import org.apache.sling.jcr.contentparser.impl.JsonTicksConverter;
import org.apache.sling.junit.remote.httpclient.RemoteTestHttpClient;
import org.apache.sling.junit.remote.testrunner.SlingRemoteTestParameters;
import org.apache.sling.junit.remote.testrunner.SlingTestsCountChecker;
import org.apache.sling.testing.tools.http.RequestCustomizer;
import org.apache.sling.testing.tools.http.RequestExecutor;
import org.apache.sling.testing.tools.sling.SlingTestBase;
import org.junit.jupiter.api.DynamicTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JUnit5 dynamic-tests based executor that talks to a remote
 *  Sling JUnit test servlet. Using this test
 *  lets a test class discover tests
 *  that the JUnit servlet can execute, execute
 *  them and report results exactly as if the tests
 *  ran locally.
 */
public class SlingRemoteDynamicTestExecutor {

    private static final Logger log = LoggerFactory.getLogger(SlingRemoteDynamicTestExecutor.class);

    private final SlingRemoteTestParameters testParameters;
    private final RemoteTestHttpClient testHttpClient;
    private final String username;
    private final String password;

    private final List<SlingRemoteTestResult> children = new LinkedList<>();

    public SlingRemoteDynamicTestExecutor(SlingRemoteTestParameters instance) {
        // Set configured username using "admin" as default credential
        final String configuredUsername = System.getProperty(SlingTestBase.TEST_SERVER_USERNAME);
        if (configuredUsername != null && !configuredUsername.trim().isEmpty()) {
            username = configuredUsername;
        } else {
            username = SlingTestBase.ADMIN;
        }

        // Set configured password using "admin" as default credential
        final String configuredPassword = System.getProperty(SlingTestBase.TEST_SERVER_PASSWORD);
        if (configuredPassword != null && !configuredPassword.trim().isEmpty()) {
            password = configuredPassword;
        } else {
            password = SlingTestBase.ADMIN;
        }

        testParameters = instance;
        testHttpClient =
                new RemoteTestHttpClient(testParameters.getJunitServletUrl(), this.username, this.password, true);
    }

    private void executeTests() throws Exception {
        // Let the parameters class customize the request if desired
        if (testParameters instanceof RequestCustomizer) {
            testHttpClient.setRequestCustomizer((RequestCustomizer) testParameters);
        }

        // Run tests remotely and get response
        final RequestExecutor executor = testHttpClient.runTests(
                testParameters.getTestClassesSelector(), testParameters.getTestMethodSelector(), "json");
        executor.assertContentType("application/json");
        final JsonArray json = Json.createReader(
                        new StringReader(JsonTicksConverter.tickToDoubleQuote(executor.getContent())))
                .readArray();

        // Response contains an array of objects identified by
        // their INFO_TYPE, extract the tests
        // based on this vlaue
        for (int i = 0; i < json.size(); i++) {
            final JsonObject obj = json.getJsonObject(i);
            if (obj.containsKey("INFO_TYPE") && "test".equals(obj.getString("INFO_TYPE"))) {
                children.add(new SlingRemoteTestResult(obj));
            }
        }

        log.info(
                "Server-side tests executed as {} at {} with path {}",
                this.username,
                testParameters.getJunitServletUrl(),
                testHttpClient.getTestExecutionPath());

        // Optionally check that number of tests is as expected
        if (testParameters instanceof SlingTestsCountChecker) {
            ((SlingTestsCountChecker) testParameters).checkNumberOfTests(children.size());
        }
    }

    public Stream<DynamicTest> streamRemoteTests() {
        try {
            executeTests();
        } catch (Exception e) {
            throw new Error(e);
        }
        return children.stream()
                .map(remoteTest -> DynamicTest.dynamicTest(remoteTest.getDescription(), () -> {
                    if (remoteTest.getFailure() != null) {
                        throw new AssertionError(remoteTest.getFailure());
                    }
                }));
    }
}
