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

package com.ning.billing.debug;

import com.ning.billing.catalog.api.BillingPeriod;
import com.ning.billing.catalog.api.Currency;
import com.ning.billing.catalog.api.InternationalPrice;
import com.ning.billing.catalog.api.Price;
import com.ning.billing.entitlement.api.billing.BillingModeType;
import com.ning.billing.invoice.api.BillingEventSet;
import com.ning.billing.invoice.api.DefaultBillingEvent;
import org.joda.time.DateTime;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.util.Date;
import java.util.UUID;

import static org.testng.Assert.assertEquals;

public class DebugEntryPointTest {

    @Test(groups = {"debug-entry", "business"})
    public void testBillingEventCarriesBusinessFieldsForInvoice() {
        DefaultBillingEvent event = new DefaultBillingEvent(
                UUID.randomUUID(),
                new DateTime(2026, 4, 1, 0, 0, 0, 0),
                "basic-monthly",
                "evergreen",
                fixedPrice("29.99"),
                BillingPeriod.MONTHLY,
                1,
                BillingModeType.IN_ADVANCE
        );

        assertEquals(event.getDescription(), "basic-monthly(evergreen)");
        assertEquals(event.getPrice(Currency.USD), new BigDecimal("29.99"));
        assertEquals(event.getBillingPeriod(), BillingPeriod.MONTHLY);
        assertEquals(event.getBillingMode(), BillingModeType.IN_ADVANCE);
    }

    @Test(groups = {"debug-entry", "business"})
    public void testBillingEventSetUsesSubscriptionThenDateOrdering() {
        UUID subscriptionA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID subscriptionB = UUID.fromString("00000000-0000-0000-0000-000000000002");

        DefaultBillingEvent aLater = new DefaultBillingEvent(subscriptionA, new DateTime(2026, 5, 1, 0, 0, 0, 0), "pro", "evergreen", fixedPrice("10.00"), BillingPeriod.MONTHLY, 1, BillingModeType.IN_ADVANCE);
        DefaultBillingEvent bEarly = new DefaultBillingEvent(subscriptionB, new DateTime(2026, 1, 1, 0, 0, 0, 0), "basic", "trial", fixedPrice("0.00"), BillingPeriod.MONTHLY, 1, BillingModeType.IN_ADVANCE);
        DefaultBillingEvent aEarly = new DefaultBillingEvent(subscriptionA, new DateTime(2026, 4, 1, 0, 0, 0, 0), "pro", "trial", fixedPrice("0.00"), BillingPeriod.MONTHLY, 1, BillingModeType.IN_ADVANCE);

        java.util.TreeSet<DefaultBillingEvent> ordered = new java.util.TreeSet<DefaultBillingEvent>();
        ordered.add(aLater);
        ordered.add(bEarly);
        ordered.add(aEarly);

        BillingEventSet events = new BillingEventSet();
        events.addAll(ordered);

        assertEquals(events.get(0).getSubscriptionId(), subscriptionA);
        assertEquals(events.get(0).getEffectiveDate(), new DateTime(2026, 4, 1, 0, 0, 0, 0));
        assertEquals(events.get(1).getSubscriptionId(), subscriptionA);
        assertEquals(events.get(2).getSubscriptionId(), subscriptionB);
        assertEquals(events.getLast().getSubscriptionId(), subscriptionB);
    }

    @Test(groups = {"debug-entry", "business"})
    public void testBillingEventCanBeShiftedToNewEffectiveDateForRebilling() {
        DateTime originalDate = new DateTime(2026, 4, 1, 0, 0, 0, 0);
        DefaultBillingEvent original = new DefaultBillingEvent(
                UUID.randomUUID(),
                originalDate,
                "legacy-plan",
                "evergreen",
                fixedPrice("19.99"),
                BillingPeriod.MONTHLY,
                1,
                BillingModeType.IN_ADVANCE
        );

        DateTime rebillFrom = new DateTime(2026, 4, 15, 0, 0, 0, 0);
        DefaultBillingEvent shifted = new DefaultBillingEvent(original, rebillFrom);

        assertEquals(shifted.getEffectiveDate(), rebillFrom);
        assertEquals(shifted.getSubscriptionId(), original.getSubscriptionId());
        assertEquals(shifted.getDescription(), original.getDescription());
        assertEquals(shifted.getPrice(Currency.USD), original.getPrice(Currency.USD));
    }

    private InternationalPrice fixedPrice(final String amount) {
        return new InternationalPrice() {
            @Override
            public Price[] getPrices() {
                return new Price[0];
            }

            @Override
            public BigDecimal getPrice(final Currency currency) {
                return new BigDecimal(amount);
            }

            @Override
            public Date getEffectiveDateForExistingSubscriptons() {
                return new Date(0L);
            }
        };
    }

}
