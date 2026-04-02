/*
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.entitlement.api;

import com.google.inject.Injector;
import com.ning.billing.account.api.AccountData;
import com.ning.billing.catalog.DefaultCatalogService;
import com.ning.billing.catalog.api.*;
import com.ning.billing.config.EntitlementConfig;
import com.ning.billing.entitlement.api.ApiTestListener.NextEvent;
import com.ning.billing.entitlement.api.billing.EntitlementBillingApi;
import com.ning.billing.entitlement.api.migration.EntitlementMigrationApi;
import com.ning.billing.entitlement.api.user.*;
import com.ning.billing.entitlement.engine.core.Engine;
import com.ning.billing.entitlement.engine.dao.EntitlementDao;
import com.ning.billing.entitlement.engine.dao.MockEntitlementDao;
import com.ning.billing.entitlement.events.EntitlementEvent;
import com.ning.billing.entitlement.events.phase.PhaseEvent;
import com.ning.billing.entitlement.events.user.ApiEvent;
import com.ning.billing.entitlement.events.user.ApiEventType;
import com.ning.billing.lifecycle.KillbillService.ServiceException;
import com.ning.billing.util.clock.Clock;
import com.ning.billing.util.clock.ClockMock;
import com.ning.billing.util.eventbus.DefaultEventBusService;
import com.ning.billing.util.eventbus.EventBusService;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.testng.Assert.*;


public abstract class TestApiBase {

    protected static final Logger log = LoggerFactory.getLogger(TestApiBase.class);

    protected static final long DAY_IN_MS = (24 * 3600 * 1000);

    protected EntitlementService entitlementService;
    protected EntitlementUserApi entitlementApi;
    protected EntitlementBillingApi billingApi;

    protected EntitlementMigrationApi migrationApi;

    protected CatalogService catalogService;
    protected EntitlementConfig config;
    protected EntitlementDao dao;
    protected ClockMock clock;
    protected EventBusService busService;

    protected AccountData accountData;
    protected Catalog catalog;
    protected ApiTestListener testListener;
    protected SubscriptionBundle bundle;
    private static final String TEST_DB_NAME = "killbill";
    private static final String TEST_DB_USER = "root";
    private static final String TEST_DB_PASSWORD = "root";
    private static final String TEST_DB_PORT = "64327";

    public static void loadSystemPropertiesFromClasspath(final String resource) {
        final URL url = TestApiBase.class.getResource(resource);
        assertNotNull(url);

        try {
            System.getProperties().load(url.openStream());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @AfterClass(groups = {"setup"})
    public void tearDown() {
        try {
            busService.getEventBus().register(testListener);
            ((DefaultEventBusService) busService).stopBus();
        } catch (Exception e) {
            log.warn("Failed to tearDown test properly ", e);
        } finally {
            stopEmbeddedMysql();
        }

    }

    @BeforeClass(groups = {"setup"})
    public void setup() {

        loadSystemPropertiesFromClasspath("/entitlement.properties");
        if (shouldStartEmbeddedMysql()) {
            startEmbeddedMysql();
        }
        final Injector g = getInjector();

        entitlementService = g.getInstance(EntitlementService.class);
        catalogService = g.getInstance(CatalogService.class);
        busService = g.getInstance(EventBusService.class);
        config = g.getInstance(EntitlementConfig.class);
        dao = g.getInstance(EntitlementDao.class);
        clock = (ClockMock) g.getInstance(Clock.class);
        try {

            ((DefaultCatalogService) catalogService).loadCatalog();
            ((DefaultEventBusService) busService).startBus();
            ((Engine) entitlementService).initialize();
            init();
        } catch (EntitlementUserApiException e) {
            Assert.fail(e.getMessage());
        } catch (ServiceException e) {
            Assert.fail(e.getMessage());
        }
    }

    protected void startEmbeddedMysql() {
        try {
            runDockerComposeUp();

            final String jdbcUrl = "jdbc:mysql://127.0.0.1:" + TEST_DB_PORT + "/" + TEST_DB_NAME + "?createDatabaseIfNotExist=true&allowMultiQueries=true";
            System.setProperty("com.ning.billing.dbi.jdbc.url", jdbcUrl);
            System.setProperty("com.ning.billing.dbi.jdbc.user", TEST_DB_USER);
            System.setProperty("com.ning.billing.dbi.jdbc.password", TEST_DB_PASSWORD);

            waitForMysqlReady(jdbcUrl, TEST_DB_USER, TEST_DB_PASSWORD);

            final String entitlementDdl = readResourceAsString("/com/ning/billing/entitlement/ddl.sql");
            final String accountDdl = readResourceAsString("/com/ning/billing/account/ddl.sql");
            final String invoiceDdl = readResourceAsString("/com/ning/billing/invoice/ddl.sql");
            final String utilDdl = readResourceAsString("/com/ning/billing/util/ddl.sql");

            executeSqlScript(jdbcUrl, TEST_DB_USER, TEST_DB_PASSWORD, entitlementDdl);
            executeSqlScript(jdbcUrl, TEST_DB_USER, TEST_DB_PASSWORD, accountDdl);
            executeSqlScript(jdbcUrl, TEST_DB_USER, TEST_DB_PASSWORD, invoiceDdl);
            executeSqlScript(jdbcUrl, TEST_DB_USER, TEST_DB_PASSWORD, utilDdl);
        } catch (Exception e) {
            Assert.fail("Failed to prepare docker MySQL for tests: " + e.getMessage(), e);
        }
    }

    protected void stopEmbeddedMysql() {
        // Keep the container running for faster iterative debug runs.
    }

    protected boolean shouldStartEmbeddedMysql() {
        return !getClass().getSimpleName().contains("Memory");
    }

    private void runDockerComposeUp() throws Exception {
        final File composeFile = resolveComposeFile();
        final ProcessBuilder pb = new ProcessBuilder("docker", "compose", "-f", composeFile.getAbsolutePath(), "up", "-d");
        pb.directory(composeFile.getParentFile());
        pb.redirectErrorStream(true);

        final Process process = pb.start();
        final String output = readProcessOutput(process.getInputStream());
        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("docker compose up failed: " + output);
        }
    }

    private File resolveComposeFile() {
        final File inModuleRoot = new File("docker-compose.test-mysql.yml");
        if (inModuleRoot.exists()) {
            return inModuleRoot.getAbsoluteFile();
        }

        final File inRepoRoot = new File("entitlement/docker-compose.test-mysql.yml");
        if (inRepoRoot.exists()) {
            return inRepoRoot.getAbsoluteFile();
        }

        throw new IllegalStateException("Missing docker compose file: docker-compose.test-mysql.yml");
    }

    private void waitForMysqlReady(final String jdbcUrl,
                                   final String user,
                                   final String password) throws Exception {
        Exception lastError = null;
        for (int i = 0; i < 60; i++) {
            Connection connection = null;
            Statement statement = null;
            try {
                connection = DriverManager.getConnection(jdbcUrl, user, password);
                statement = connection.createStatement();
                statement.execute("SELECT 1");
                return;
            } catch (Exception e) {
                lastError = e;
                Thread.sleep(1000L);
            } finally {
                if (statement != null) {
                    try {
                        statement.close();
                    } catch (Exception ignore) {
                    }
                }
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        }
        throw new IllegalStateException("MySQL is not ready after 60s", lastError);
    }

    private void executeSqlScript(final String jdbcUrl,
                                  final String user,
                                  final String password,
                                  final String ddl) throws SQLException {
        Connection connection = null;
        Statement statement = null;
        try {
            connection = DriverManager.getConnection(jdbcUrl, user, password);
            statement = connection.createStatement();
            statement.execute(ddl);
        } finally {
            if (statement != null) {
                try {
                    statement.close();
                } catch (Exception ignore) {
                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private String readProcessOutput(final InputStream inputStream) throws IOException {
        final BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        final StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private String readResourceAsString(final String resourcePath) throws IOException {
        final InputStream inputStream = TestApiBase.class.getResourceAsStream(resourcePath);
        if (inputStream == null) {
            throw new IOException("Resource not found: " + resourcePath);
        }

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        inputStream.close();
        return outputStream.toString("UTF-8");
    }

    protected abstract Injector getInjector();

    private void init() throws EntitlementUserApiException {
        accountData = getAccountData();
        assertNotNull(accountData);

        catalog = catalogService.getCatalog();
        assertNotNull(catalog);


        testListener = new ApiTestListener(busService.getEventBus());
        entitlementApi = entitlementService.getUserApi();
        billingApi = entitlementService.getBillingApi();
        migrationApi = entitlementService.getMigrationApi();

    }

    @BeforeMethod(groups = {"setup"})
    public void setupTest() {

        log.warn("\n");
        log.warn("RESET TEST FRAMEWORK\n\n");

        testListener.reset();

        clock.resetDeltaFromReality();
        ((MockEntitlementDao) dao).reset();
        try {
            busService.getEventBus().register(testListener);
            UUID accountId = UUID.randomUUID();
            bundle = entitlementApi.createBundleForAccount(accountId, "myDefaultBundle");
        } catch (Exception e) {
            Assert.fail(e.getMessage());
        }
        assertNotNull(bundle);

        ((Engine) entitlementService).start();
    }

    @AfterMethod(groups = {"setup"})
    public void cleanupTest() {


        ((Engine) entitlementService).stop();
        log.warn("DONE WITH TEST\n");
    }

    protected SubscriptionData createSubscription(final String productName,
                                                  final BillingPeriod term,
                                                  final String planSet) throws EntitlementUserApiException {
        testListener.pushExpectedEvent(NextEvent.CREATE);
        SubscriptionData subscription = (SubscriptionData) entitlementApi.createSubscription(bundle.getId(),
                new PlanPhaseSpecifier(productName, ProductCategory.BASE, term, planSet, null),
                clock.getUTCNow());
        assertNotNull(subscription);
        assertTrue(testListener.isCompleted(5000));
        return subscription;
    }

    protected void checkNextPhaseChange(SubscriptionData subscription,
                                        int expPendingEvents,
                                        DateTime expPhaseChange) {

        List<EntitlementEvent> events = dao.getPendingEventsForSubscription(subscription.getId());
        assertNotNull(events);
        printEvents(events);
        assertEquals(events.size(), expPendingEvents);
        if (events.size() > 0 && expPhaseChange != null) {
            boolean foundPhase = false;
            boolean foundChange = false;

            for (EntitlementEvent cur : events) {
                if (cur instanceof PhaseEvent) {
                    assertEquals(foundPhase, false);
                    foundPhase = true;
                    assertEquals(cur.getEffectiveDate(), expPhaseChange);
                } else if (cur instanceof ApiEvent) {
                    ApiEvent uEvent = (ApiEvent) cur;
                    assertEquals(ApiEventType.CHANGE, uEvent.getEventType());
                    assertEquals(foundChange, false);
                    foundChange = true;
                } else {
                    assertFalse(true);
                }
            }
        }
    }


    protected void assertDateWithin(DateTime in,
                                    DateTime lower,
                                    DateTime upper) {
        assertTrue(in.isEqual(lower) || in.isAfter(lower));
        assertTrue(in.isEqual(upper) || in.isBefore(upper));
    }

    protected Duration getDurationDay(final int days) {
        Duration result = new Duration() {
            @Override
            public TimeUnit getUnit() {
                return TimeUnit.DAYS;
            }

            @Override
            public int getNumber() {
                return days;
            }
        };
        return result;
    }

    protected Duration getDurationMonth(final int months) {
        Duration result = new Duration() {
            @Override
            public TimeUnit getUnit() {
                return TimeUnit.MONTHS;
            }

            @Override
            public int getNumber() {
                return months;
            }
        };
        return result;
    }


    protected Duration getDurationYear(final int years) {
        Duration result = new Duration() {
            @Override
            public TimeUnit getUnit() {
                return TimeUnit.YEARS;
            }

            @Override
            public int getNumber() {
                return years;
            }
        };
        return result;
    }

    protected AccountData getAccountData() {
        AccountData accountData = new AccountData() {
            @Override
            public String getName() {
                return "firstName lastName";
            }

            @Override
            public int getFirstNameLength() {
                return "firstName".length();
            }

            @Override
            public String getEmail() {
                return "accountName@yahoo.com";
            }

            @Override
            public String getPhone() {
                return "4152876341";
            }

            @Override
            public String getExternalKey() {
                return "k123456";
            }

            @Override
            public int getBillCycleDay() {
                return 1;
            }

            @Override
            public Currency getCurrency() {
                return Currency.USD;
            }

            @Override
            public String getPaymentProviderName() {
                return "Paypal";
            }
        };
        return accountData;
    }

    protected PlanPhaseSpecifier getProductSpecifier(final String productName,
                                                     final String priceList,
                                                     final BillingPeriod term,
                                                     final PhaseType phaseType) {
        return new PlanPhaseSpecifier(productName, ProductCategory.BASE, term, priceList, phaseType);
    }

    protected void printEvents(List<EntitlementEvent> events) {
        for (EntitlementEvent cur : events) {
            log.debug("Inspect event " + cur);
        }
    }

    protected void printSubscriptionTransitions(List<SubscriptionTransition> transitions) {
        for (SubscriptionTransition cur : transitions) {
            log.debug("Transition " + cur);
        }
    }

}
